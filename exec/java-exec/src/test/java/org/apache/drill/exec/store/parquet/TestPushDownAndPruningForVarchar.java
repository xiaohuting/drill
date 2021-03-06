/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.exec.store.parquet;

import org.apache.commons.io.FileUtils;
import org.apache.drill.categories.ParquetTest;
import org.apache.drill.categories.UnlikelyTest;
import org.apache.drill.exec.ExecConstants;
import org.apache.drill.test.ClusterFixture;
import org.apache.drill.test.ClusterFixtureBuilder;
import org.apache.drill.test.ClusterTest;
import org.apache.drill.test.QueryBuilder;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@Category({ParquetTest.class, UnlikelyTest.class})
public class TestPushDownAndPruningForVarchar extends ClusterTest {

  private static File fileStore;

  @BeforeClass
  public static void setup() throws Exception {
    ClusterFixtureBuilder builder = ClusterFixture.builder(dirTestWatcher);
    /*
      Contains two data files generated by Drill 1.13.0 version
      (before upgrade to Parquet lib 1.10.0).
      Each file has two varchar columns.

      0_0_1.parquet       0_0_2.parquet
      -----------         -----------
      part | val          part | val
      -----------         -----------
      A    | A1           B    | B1
      A    | A2           B    | B2

      Also contains .drill.parquet_metadata generated for these two files.
     */
    fileStore = dirTestWatcher.copyResourceToRoot(Paths.get("parquet", "varchar_gen_1_13_0"));
    startCluster(builder);
  }

  @Test
  public void testOldFilesPruningWithAndWithoutMeta() throws Exception {
    String tableNoMeta = createTable("varchar_pruning_old_without_meta", true);
    String tableWithMeta = createTable("varchar_pruning_old_with_meta", false);

    Map<String, String> properties = new HashMap<>();
    properties.put(tableNoMeta, "false");
    properties.put(tableWithMeta, "true");

    try {
      for (Map.Entry<String, String> property : properties.entrySet()) {
        for (String optionValue : Arrays.asList("true", "false", "")) {
          client.alterSession(ExecConstants.PARQUET_READER_STRINGS_SIGNED_MIN_MAX, optionValue);
          String query = String.format("select * from %s where part = 'A'", property.getKey());
          String plan = client.queryBuilder().sql(query).explainText();
          assertTrue(plan.contains("numRowGroups=1"));
          assertTrue(plan.contains(String.format("usedMetadataFile=%s", property.getValue())));
          assertFalse(plan.contains("Filter"));

          client.testBuilder()
            .sqlQuery(query)
            .unOrdered()
            .baselineColumns("part", "val")
            .baselineValues("A", "A1")
            .baselineValues("A", "A2")
            .go();
        }
      }
    } finally {
      client.resetSession(ExecConstants.PARQUET_READER_STRINGS_SIGNED_MIN_MAX);

      properties.keySet().
        forEach(k -> client.runSqlSilently(String.format("drop table if exists %s", k)));
    }
  }

  @Test
  public void testOldFilesPruningWithNewMeta() throws Exception {
    String table = createTable("varchar_pruning_old_with_new_meta", true);

    try {
      for (String optionValue : Arrays.asList("true", "false", "")) {
        client.alterSession(ExecConstants.PARQUET_READER_STRINGS_SIGNED_MIN_MAX, optionValue);
        queryBuilder().sql(String.format("refresh table metadata %s", table)).run();
        String query = String.format("select * from %s where part = 'A'", table);
        String plan = client.queryBuilder().sql(query).explainText();
        assertTrue(plan.contains("numRowGroups=1"));
        assertTrue(plan.contains("usedMetadataFile=true"));
        assertFalse(plan.contains("Filter"));

        client.testBuilder()
          .sqlQuery(query)
          .unOrdered()
          .baselineColumns("part", "val")
          .baselineValues("A", "A1")
          .baselineValues("A", "A2")
          .go();
      }
    } finally {
      client.resetSession(ExecConstants.PARQUET_READER_STRINGS_SIGNED_MIN_MAX);
      client.runSqlSilently(String.format("drop table if exists %s", table));
    }
  }

  @Test
  public void testNewFilesPruningNoMeta() throws Exception {
    String oldTable = createTable("varchar_pruning_old_without_meta", true);
    String newTable = "dfs.`tmp`.`varchar_pruning_new_without_meta`";

    try {
      queryBuilder().sql(String.format("create table %s partition by (part) as select * from %s", newTable, oldTable)).run();

      for (String optionValue : Arrays.asList("true", "false", "")) {
        client.alterSession(ExecConstants.PARQUET_READER_STRINGS_SIGNED_MIN_MAX, optionValue);
        String query = String.format("select * from %s where part = 'A'", newTable);
        String plan = client.queryBuilder().sql(query).explainText();
        assertTrue(plan.contains("numRowGroups=1"));
        assertTrue(plan.contains("usedMetadataFile=false"));
        assertFalse(plan.contains("Filter"));

        client.testBuilder()
          .sqlQuery(query)
          .unOrdered()
          .baselineColumns("part", "val")
          .baselineValues("A", "A1")
          .baselineValues("A", "A2")
          .go();
      }
    } finally {
      client.resetSession(ExecConstants.PARQUET_READER_STRINGS_SIGNED_MIN_MAX);
      client.runSqlSilently(String.format("drop table if exists %s", oldTable));
      client.runSqlSilently(String.format("drop table if exists %s", newTable));
    }
  }

  @Test
  public void testNewFilesPruningWithNewMeta() throws Exception {
    String oldTable = createTable("varchar_pruning_old_without_meta", true);
    String newTable = "dfs.`tmp`.`varchar_pruning_new_with_new_meta`";

    try {
      queryBuilder().sql(String.format("create table %s partition by (part) as select * from %s", newTable, oldTable)).run();
      queryBuilder().sql(String.format("refresh table metadata %s", newTable)).run();

      for (String optionValue : Arrays.asList("true", "false", "")) {
        client.alterSession(ExecConstants.PARQUET_READER_STRINGS_SIGNED_MIN_MAX, optionValue);
        String query = String.format("select * from %s where part = 'A'", newTable);
        String plan = client.queryBuilder().sql(query).explainText();
        assertTrue(plan.contains("numRowGroups=1"));
        assertTrue(plan.contains("usedMetadataFile=true"));
        assertFalse(plan.contains("Filter"));

        client.testBuilder()
          .sqlQuery(query)
          .unOrdered()
          .baselineColumns("part", "val")
          .baselineValues("A", "A1")
          .baselineValues("A", "A2")
          .go();
      }
    } finally {
      client.resetSession(ExecConstants.PARQUET_READER_STRINGS_SIGNED_MIN_MAX);
      client.runSqlSilently(String.format("drop table if exists %s", oldTable));
      client.runSqlSilently(String.format("drop table if exists %s", newTable));
    }
  }

  @Test
  public void testNewFilesPruningWithNullPartition() throws Exception {
    String table = "dfs.`tmp`.`varchar_pruning_new_with_null_partition`";

    try {
      queryBuilder().sql(String.format("create table %s partition by (col_vrchr) as " +
        "select * from cp.`parquet/alltypes_optional.parquet`", table)).run();

      String query = String.format("select * from %s where col_vrchr = 'Nancy Cloke'", table);

      String plan = client.queryBuilder().sql(query).explainText();
      assertTrue(plan.contains("usedMetadataFile=false"));
      assertFalse(plan.contains("Filter"));

      QueryBuilder.QuerySummary result = client.queryBuilder().sql(query).run();
      assertTrue(result.succeeded());
      assertEquals(1, result.recordCount());

      queryBuilder().sql(String.format("refresh table metadata %s", table)).run();

      plan = client.queryBuilder().sql(query).explainText();
      assertTrue(plan.contains("usedMetadataFile=true"));
      assertFalse(plan.contains("Filter"));

      result = client.queryBuilder().sql(query).run();
      assertTrue(result.succeeded());
      assertEquals(1, result.recordCount());
    } finally {
      client.runSqlSilently(String.format("drop table if exists %s", table));
    }
  }

  @Test
  public void testOldFilesPushDownNoMeta() throws Exception {
    String table = createTable("varchar_push_down_old_without_meta", true);

    Map<String, String> properties = new HashMap<>();
    properties.put("true", "numRowGroups=1");
    properties.put("false", "numRowGroups=2");

    try {
      for (Map.Entry<String, String> property : properties.entrySet()) {
        client.alterSession(ExecConstants.PARQUET_READER_STRINGS_SIGNED_MIN_MAX, property.getKey());
        String query = String.format("select * from %s where val = 'A1'", table);

        String plan = client.queryBuilder().sql(query).explainText();
        assertTrue(plan.contains(property.getValue()));
        assertTrue(plan.contains("usedMetadataFile=false"));

        client.testBuilder()
          .sqlQuery(query)
          .unOrdered()
          .baselineColumns("part", "val")
          .baselineValues("A", "A1")
          .go();
      }
    } finally {
      client.resetSession(ExecConstants.PARQUET_READER_STRINGS_SIGNED_MIN_MAX);
      client.runSqlSilently(String.format("drop table if exists %s", table));
    }
  }

  @Test
  public void testOldFilesPushDownWithOldMeta() throws Exception {
    String table = createTable("varchar_push_down_old_with_old_meta", false);

    Map<String, String> properties = new HashMap<>();
    properties.put("false", "numRowGroups=2");
    properties.put("true", "numRowGroups=1");

    try {
      for (Map.Entry<String, String> property : properties.entrySet()) {
        client.alterSession(ExecConstants.PARQUET_READER_STRINGS_SIGNED_MIN_MAX, property.getKey());
        String query = String.format("select * from %s where val = 'A1'", table);

        String plan = client.queryBuilder().sql(query).explainText();
        assertTrue(plan.contains(property.getValue()));
        assertTrue(plan.contains("usedMetadataFile=true"));

        client.testBuilder()
          .sqlQuery(query)
          .unOrdered()
          .baselineColumns("part", "val")
          .baselineValues("A", "A1")
          .go();
      }
    } finally {
      client.resetSession(ExecConstants.PARQUET_READER_STRINGS_SIGNED_MIN_MAX);
      client.runSqlSilently(String.format("drop table if exists %s", table));
    }
  }

  @Test
  public void testNewFilesPushDownNoMeta() throws Exception {
    String oldTable = createTable("varchar_push_down_old_without_meta", true);
    String newTable = "dfs.`tmp`.`varchar_push_down_new_without_meta`";

    try {
      queryBuilder().sql(String.format("create table %s partition by (part) as select * from %s", newTable, oldTable)).run();

      for (String optionValue : Arrays.asList("true", "false", "")) {
        client.alterSession(ExecConstants.PARQUET_READER_STRINGS_SIGNED_MIN_MAX, optionValue);
        String query = String.format("select * from %s where val = 'A1'", newTable);
        String plan = client.queryBuilder().sql(query).explainText();
        assertTrue(plan.contains("numRowGroups=1"));
        assertTrue(plan.contains("usedMetadataFile=false"));

        client.testBuilder()
          .sqlQuery(query)
          .unOrdered()
          .baselineColumns("part", "val")
          .baselineValues("A", "A1")
          .go();
      }
    } finally {
      client.resetSession(ExecConstants.PARQUET_READER_STRINGS_SIGNED_MIN_MAX);
      client.runSqlSilently(String.format("drop table if exists %s", oldTable));
      client.runSqlSilently(String.format("drop table if exists %s", newTable));
    }
  }

  @Test
  public void testNewFilesPushDownWithMeta() throws Exception {
    String oldTable = createTable("varchar_push_down_old_without_meta", true);
    String newTable = "dfs.`tmp`.`varchar_push_down_new_with_meta`";

    try {
      queryBuilder().sql(String.format("create table %s partition by (part) as select * from %s", newTable, oldTable)).run();
      queryBuilder().sql(String.format("refresh table metadata %s", newTable)).run();
      String query = String.format("select * from %s where val = 'A1'", newTable);
      // metadata for binary is allowed only after Drill 1.15.0
      // set string signed option to true, to read it on current Drill 1.15.0-SNAPSHOT version
      client.alterSession(ExecConstants.PARQUET_READER_STRINGS_SIGNED_MIN_MAX, "true");
      String plan = client.queryBuilder().sql(query).explainText();
      assertTrue(plan.contains("numRowGroups=1"));
      assertTrue(plan.contains("usedMetadataFile=true"));

      client.testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .baselineColumns("part", "val")
        .baselineValues("A", "A1")
        .go();
    } finally {
      client.resetSession(ExecConstants.PARQUET_READER_STRINGS_SIGNED_MIN_MAX);
      client.runSqlSilently(String.format("drop table if exists %s", oldTable));
      client.runSqlSilently(String.format("drop table if exists %s", newTable));
    }
  }

  private String createTable(String tableName, boolean removeMetadata) throws IOException {
    File rootDir = dirTestWatcher.getRootDir();
    File table = new File(rootDir, String.format("%s_%s", tableName, UUID.randomUUID()));
    FileUtils.copyDirectory(fileStore, table);
    File metadata = new File(table, ".drill.parquet_metadata");
    if (removeMetadata) {
      assertTrue(metadata.delete());
    } else {
      // metadata modification time should be higher
      // than directory modification time otherwise metadata file will be regenerated
      assertTrue(metadata.setLastModified(Instant.now().toEpochMilli()));
    }
    return String.format("dfs.`root`.`%s`", table.getName());
  }

}
