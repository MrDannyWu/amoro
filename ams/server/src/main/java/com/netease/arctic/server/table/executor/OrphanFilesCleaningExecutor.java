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

package com.netease.arctic.server.table.executor;

import static com.netease.arctic.server.optimizing.maintainer.TableMaintainer.ofTable;

import com.netease.arctic.AmoroTable;
import com.netease.arctic.server.optimizing.maintainer.TableMaintainer;
import com.netease.arctic.server.table.TableConfiguration;
import com.netease.arctic.server.table.TableManager;
import com.netease.arctic.server.table.TableRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OrphanFilesCleaningExecutor extends BaseTableExecutor {
  private static final Logger LOG = LoggerFactory.getLogger(OrphanFilesCleaningExecutor.class);
  // same as org.apache.iceberg.flink.sink.IcebergFilesCommitter#FLINK_JOB_ID

  private static final long INTERVAL = 24 * 60 * 60 * 1000L;

  public OrphanFilesCleaningExecutor(TableManager tableRuntimes, int poolSize) {
    super(tableRuntimes, poolSize);
  }

  @Override
  protected long getNextExecutingTime(TableRuntime tableRuntime) {
    return INTERVAL;
  }

  @Override
  protected boolean enabled(TableRuntime tableRuntime) {
    return tableRuntime.getTableConfiguration().isCleanOrphanEnabled();
  }

  @Override
  public void handleConfigChanged(TableRuntime tableRuntime, TableConfiguration originalConfig) {
    scheduleIfNecessary(tableRuntime, getStartDelay());
  }

  @Override
  public void execute(TableRuntime tableRuntime) {
    try {
      LOG.info("{} start cleaning orphan files", tableRuntime.getTableIdentifier());
      // clear data files
      AmoroTable<?> amoroTable = loadTable(tableRuntime);
      TableMaintainer tableMaintainer = ofTable(amoroTable);
      tableMaintainer.cleanOrphanFiles(tableRuntime);
    } catch (Throwable t) {
      LOG.error("{} failed to clean orphan file", tableRuntime.getTableIdentifier(), t);
    }
  }
}
