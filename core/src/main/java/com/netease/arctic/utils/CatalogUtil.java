/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netease.arctic.utils;

import static com.netease.arctic.ams.api.properties.CatalogMetaProperties.CATALOG_TYPE_AMS;
import static com.netease.arctic.ams.api.properties.CatalogMetaProperties.CATALOG_TYPE_CUSTOM;
import static com.netease.arctic.ams.api.properties.CatalogMetaProperties.CATALOG_TYPE_GLUE;
import static com.netease.arctic.ams.api.properties.CatalogMetaProperties.CATALOG_TYPE_HADOOP;
import static com.netease.arctic.ams.api.properties.CatalogMetaProperties.CATALOG_TYPE_HIVE;
import static com.netease.arctic.ams.api.properties.CatalogMetaProperties.STORAGE_CONFIGS_KEY_TYPE;
import static com.netease.arctic.ams.api.properties.CatalogMetaProperties.STORAGE_CONFIGS_VALUE_TYPE_HADOOP;
import static com.netease.arctic.ams.api.properties.CatalogMetaProperties.STORAGE_CONFIGS_VALUE_TYPE_HDFS_LEGACY;

import com.netease.arctic.ams.api.CatalogMeta;
import com.netease.arctic.ams.api.TableFormat;
import com.netease.arctic.ams.api.TableMeta;
import com.netease.arctic.ams.api.properties.CatalogMetaProperties;
import com.netease.arctic.catalog.CatalogLoader;
import com.netease.arctic.io.ArcticFileIO;
import com.netease.arctic.op.ArcticHadoopTableOperations;
import com.netease.arctic.op.ArcticTableOperations;
import com.netease.arctic.table.TableIdentifier;
import com.netease.arctic.table.TableMetaStore;
import com.netease.arctic.table.TableProperties;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.Table;
import org.apache.iceberg.hadoop.HadoopTableOperations;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;
import org.apache.iceberg.relocated.com.google.common.collect.Sets;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.util.PropertyUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class CatalogUtil {

  private static final Logger LOG = LoggerFactory.getLogger(CatalogUtil.class);

  /** Return table format set catalog supported. */
  public static Set<TableFormat> tableFormats(CatalogMeta meta) {
    return tableFormats(meta.getCatalogType(), meta.getCatalogProperties());
  }

  /** Return table format set catalog supported. */
  public static Set<TableFormat> tableFormats(
      String metastoreType, Map<String, String> catalogProperties) {
    if (catalogProperties != null
        && catalogProperties.containsKey(CatalogMetaProperties.TABLE_FORMATS)) {
      String tableFormatsProperty = catalogProperties.get(CatalogMetaProperties.TABLE_FORMATS);
      return Arrays.stream(tableFormatsProperty.split(","))
          .map(
              tableFormatString ->
                  TableFormat.valueOf(tableFormatString.trim().toUpperCase(Locale.ROOT)))
          .collect(Collectors.toSet());
    } else {
      // Generate table format from catalog type for compatibility with older versions
      switch (metastoreType) {
        case CATALOG_TYPE_AMS:
          return Sets.newHashSet(TableFormat.MIXED_ICEBERG);
        case CATALOG_TYPE_CUSTOM:
        case CATALOG_TYPE_HADOOP:
        case CATALOG_TYPE_GLUE:
          return Sets.newHashSet(TableFormat.ICEBERG);
        case CATALOG_TYPE_HIVE:
          return Sets.newHashSet(TableFormat.MIXED_HIVE);
        default:
          throw new IllegalArgumentException("Unsupported catalog type:" + metastoreType);
      }
    }
  }

  /** Merge catalog properties in client side into catalog meta. */
  public static void mergeCatalogProperties(CatalogMeta meta, Map<String, String> properties) {
    if (meta.getCatalogProperties() == null) {
      meta.setCatalogProperties(Maps.newHashMap());
    }
    if (properties != null) {
      properties.forEach(meta::putToCatalogProperties);
    }
  }

  /**
   * add initialize properties for iceberg catalog
   *
   * @param catalogName - catalog name
   * @param metastoreType - metastore type
   * @param properties - catalog properties
   * @return catalog properties with initialize properties.
   */
  public static Map<String, String> withIcebergCatalogInitializeProperties(
      String catalogName, String metastoreType, Map<String, String> properties) {
    Map<String, String> icebergCatalogProperties = Maps.newHashMap(properties);
    icebergCatalogProperties.put(
        org.apache.iceberg.CatalogUtil.ICEBERG_CATALOG_TYPE, metastoreType);
    if (CatalogMetaProperties.CATALOG_TYPE_GLUE.equals(metastoreType)) {
      icebergCatalogProperties.put(CatalogProperties.CATALOG_IMPL, CatalogLoader.GLUE_CATALOG_IMPL);
    }
    if (CATALOG_TYPE_AMS.equalsIgnoreCase(metastoreType)) {
      icebergCatalogProperties.put(CatalogProperties.WAREHOUSE_LOCATION, catalogName);
      if (!icebergCatalogProperties.containsKey(CatalogProperties.CATALOG_IMPL)) {
        icebergCatalogProperties.put(CatalogProperties.CATALOG_IMPL, RESTCatalog.class.getName());
      }
    }

    if (CATALOG_TYPE_CUSTOM.equalsIgnoreCase(metastoreType)) {
      Preconditions.checkArgument(
          icebergCatalogProperties.containsKey(CatalogProperties.CATALOG_IMPL),
          "Custom catalog properties must contains " + CatalogProperties.CATALOG_IMPL);
    }

    if (icebergCatalogProperties.containsKey(CatalogProperties.CATALOG_IMPL)) {
      icebergCatalogProperties.remove(org.apache.iceberg.CatalogUtil.ICEBERG_CATALOG_TYPE);
    }

    return icebergCatalogProperties;
  }

  /** Build {@link TableMetaStore} from catalog meta. */
  public static TableMetaStore buildMetaStore(CatalogMeta catalogMeta) {
    // load storage configs
    TableMetaStore.Builder builder = TableMetaStore.builder();
    if (catalogMeta.getStorageConfigs() != null) {
      Map<String, String> storageConfigs = catalogMeta.getStorageConfigs();
      if (CatalogMetaProperties.STORAGE_CONFIGS_VALUE_TYPE_HADOOP.equalsIgnoreCase(
          CatalogUtil.getCompatibleStorageType(storageConfigs))) {
        String coreSite = storageConfigs.get(CatalogMetaProperties.STORAGE_CONFIGS_KEY_CORE_SITE);
        String hdfsSite = storageConfigs.get(CatalogMetaProperties.STORAGE_CONFIGS_KEY_HDFS_SITE);
        String hiveSite = storageConfigs.get(CatalogMetaProperties.STORAGE_CONFIGS_KEY_HIVE_SITE);
        builder
            .withBase64CoreSite(coreSite)
            .withBase64MetaStoreSite(hiveSite)
            .withBase64HdfsSite(hdfsSite);
      }
    }

    boolean loadAuthFromAMS =
        PropertyUtil.propertyAsBoolean(
            catalogMeta.getCatalogProperties(),
            CatalogMetaProperties.LOAD_AUTH_FROM_AMS,
            CatalogMetaProperties.LOAD_AUTH_FROM_AMS_DEFAULT);
    // load auth configs from ams
    if (loadAuthFromAMS) {
      if (catalogMeta.getAuthConfigs() != null) {
        Map<String, String> authConfigs = catalogMeta.getAuthConfigs();
        String authType = authConfigs.get(CatalogMetaProperties.AUTH_CONFIGS_KEY_TYPE);
        LOG.info("TableMetaStore use auth config in catalog meta, authType is {}", authType);
        if (CatalogMetaProperties.AUTH_CONFIGS_VALUE_TYPE_SIMPLE.equalsIgnoreCase(authType)) {
          String hadoopUsername =
              authConfigs.get(CatalogMetaProperties.AUTH_CONFIGS_KEY_HADOOP_USERNAME);
          builder.withSimpleAuth(hadoopUsername);
        } else if (CatalogMetaProperties.AUTH_CONFIGS_VALUE_TYPE_KERBEROS.equalsIgnoreCase(
            authType)) {
          String krb5 = authConfigs.get(CatalogMetaProperties.AUTH_CONFIGS_KEY_KRB5);
          String keytab = authConfigs.get(CatalogMetaProperties.AUTH_CONFIGS_KEY_KEYTAB);
          String principal = authConfigs.get(CatalogMetaProperties.AUTH_CONFIGS_KEY_PRINCIPAL);
          builder.withBase64KrbAuth(keytab, krb5, principal);
        }
      }
    }

    // cover auth configs from ams with auth configs in properties
    String authType =
        catalogMeta.getCatalogProperties().get(CatalogMetaProperties.AUTH_CONFIGS_KEY_TYPE);
    if (StringUtils.isNotEmpty(authType)) {
      LOG.info("TableMetaStore use auth config in properties, authType is {}", authType);
      if (CatalogMetaProperties.AUTH_CONFIGS_VALUE_TYPE_SIMPLE.equalsIgnoreCase(authType)) {
        String hadoopUsername =
            catalogMeta
                .getCatalogProperties()
                .get(CatalogMetaProperties.AUTH_CONFIGS_KEY_HADOOP_USERNAME);
        builder.withSimpleAuth(hadoopUsername);
      } else if (CatalogMetaProperties.AUTH_CONFIGS_VALUE_TYPE_KERBEROS.equalsIgnoreCase(
          authType)) {
        String krb5 =
            catalogMeta.getCatalogProperties().get(CatalogMetaProperties.AUTH_CONFIGS_KEY_KRB5);
        String keytab =
            catalogMeta.getCatalogProperties().get(CatalogMetaProperties.AUTH_CONFIGS_KEY_KEYTAB);
        String principal =
            catalogMeta
                .getCatalogProperties()
                .get(CatalogMetaProperties.AUTH_CONFIGS_KEY_PRINCIPAL);
        builder.withBase64KrbAuth(keytab, krb5, principal);
      }
    }

    return builder.build();
  }

  /** Wrap table operation with arctic authorization logic for {@link Table}. */
  public static Table useArcticTableOperations(
      Table table, String tableLocation, ArcticFileIO arcticFileIO, Configuration configuration) {
    if (table instanceof org.apache.iceberg.BaseTable) {
      org.apache.iceberg.BaseTable baseTable = (org.apache.iceberg.BaseTable) table;
      if (baseTable.operations() instanceof HadoopTableOperations) {
        return new org.apache.iceberg.BaseTable(
            new ArcticHadoopTableOperations(new Path(tableLocation), arcticFileIO, configuration),
            table.name());
      } else {
        return new org.apache.iceberg.BaseTable(
            new ArcticTableOperations(((BaseTable) table).operations(), arcticFileIO),
            table.name());
      }
    }
    return table;
  }

  /**
   * merge properties of table level in catalog properties to table(properties key start with
   * table.)
   *
   * @param tableProperties properties in table
   * @param catalogProperties properties in catalog
   * @return merged table properties
   */
  public static Map<String, String> mergeCatalogPropertiesToTable(
      Map<String, String> tableProperties, Map<String, String> catalogProperties) {
    Map<String, String> mergedProperties =
        catalogProperties.entrySet().stream()
            .filter(e -> e.getKey().startsWith(CatalogMetaProperties.TABLE_PROPERTIES_PREFIX))
            .collect(
                Collectors.toMap(
                    e ->
                        e.getKey()
                            .substring(CatalogMetaProperties.TABLE_PROPERTIES_PREFIX.length()),
                    Map.Entry::getValue));

    if (!PropertyUtil.propertyAsBoolean(
        tableProperties,
        TableProperties.ENABLE_LOG_STORE,
        TableProperties.ENABLE_LOG_STORE_DEFAULT)) {
      mergedProperties =
          mergedProperties.entrySet().stream()
              .filter(
                  e -> !e.getKey().startsWith(CatalogMetaProperties.LOG_STORE_PROPERTIES_PREFIX))
              .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    String optimizationEnabled =
        tableProperties.getOrDefault(
            TableProperties.ENABLE_SELF_OPTIMIZING,
            mergedProperties.getOrDefault(
                TableProperties.ENABLE_SELF_OPTIMIZING,
                String.valueOf(TableProperties.ENABLE_SELF_OPTIMIZING_DEFAULT)));
    if (!Boolean.parseBoolean(optimizationEnabled)) {
      mergedProperties =
          mergedProperties.entrySet().stream()
              .filter(e -> !e.getKey().startsWith(CatalogMetaProperties.OPTIMIZE_PROPERTIES_PREFIX))
              .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
      // maintain 'optimize.enable' flag as false in table properties
      mergedProperties.put(TableProperties.ENABLE_SELF_OPTIMIZING, optimizationEnabled);
    }
    mergedProperties.putAll(tableProperties);

    return mergedProperties;
  }

  public static TableIdentifier tableId(TableMeta tableMeta) {
    return TableIdentifier.of(
        tableMeta.getTableIdentifier().getCatalog(),
        tableMeta.getTableIdentifier().getDatabase(),
        tableMeta.getTableIdentifier().getTableName());
  }

  public static com.netease.arctic.ams.api.TableIdentifier amsTaleId(
      TableIdentifier tableIdentifier) {
    return new com.netease.arctic.ams.api.TableIdentifier(
        tableIdentifier.getCatalog(),
        tableIdentifier.getDatabase(),
        tableIdentifier.getTableName());
  }

  /**
   * Get storage type compatible with history storage type `hdfs`, which is `Hadoop` now.
   *
   * @param conf - configurations containing `storage.type`
   * @return storage type, return `Hadoop` if `storage.type` is `hdfs`, return null if
   *     `storage.type` not exist.
   */
  public static String getCompatibleStorageType(Map<String, String> conf) {
    if (STORAGE_CONFIGS_VALUE_TYPE_HDFS_LEGACY.equals(conf.get(STORAGE_CONFIGS_KEY_TYPE))) {
      return STORAGE_CONFIGS_VALUE_TYPE_HADOOP;
    }
    return conf.get(STORAGE_CONFIGS_KEY_TYPE);
  }

  /**
   * Copy property from source properties to target properties, support changing the key name.
   *
   * @param fromProperties - from these properties
   * @param toProperties - to these properties
   * @param fromKey - from key
   * @param toKey - to key
   */
  public static <T> void copyProperty(
      Map<String, String> fromProperties,
      Map<String, T> toProperties,
      String fromKey,
      String toKey) {
    if (StringUtils.isNotEmpty(fromProperties.get(fromKey))) {
      toProperties.put(toKey, (T) fromProperties.get(fromKey));
    }
  }
}
