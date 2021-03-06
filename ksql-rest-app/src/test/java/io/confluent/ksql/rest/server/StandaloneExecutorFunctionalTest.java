/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.rest.server;

import static io.confluent.ksql.serde.DataSource.DataSourceSerDe.AVRO;
import static io.confluent.ksql.serde.DataSource.DataSourceSerDe.JSON;

import com.google.common.collect.ImmutableMap;
import io.confluent.common.utils.IntegrationTest;
import io.confluent.ksql.integration.IntegrationTestHarness;
import io.confluent.ksql.test.util.KsqlIdentifierTestUtil;
import io.confluent.ksql.util.OrderDataProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.connect.data.Schema;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;

@Category({IntegrationTest.class})
public class StandaloneExecutorFunctionalTest {

  @ClassRule
  public static final IntegrationTestHarness TEST_HARNESS = IntegrationTestHarness.build();

  @ClassRule
  public static final TemporaryFolder TMP = new TemporaryFolder();

  @Rule
  public final ExpectedException expectedException = ExpectedException.none();

  private static final String AVRO_TOPIC = "avro-topic";
  private static final String JSON_TOPIC = "json-topic";
  private static int DATA_SIZE;
  private static Schema DATA_SCHEMA;

  private Path queryFile;
  private StandaloneExecutor standalone;
  private String s1;
  private String s2;
  private String t1;

  @BeforeClass
  public static void classSetUp() {
    final OrderDataProvider provider = new OrderDataProvider();
    TEST_HARNESS.produceRows(AVRO_TOPIC, provider, AVRO);
    TEST_HARNESS.produceRows(JSON_TOPIC, provider, JSON);
    DATA_SIZE = provider.data().size();
    DATA_SCHEMA = provider.schema();
  }

  @Before
  public void setUp() throws Exception {
    queryFile = TMP.newFile().toPath();

    standalone = StandaloneExecutorFactory.create(defaultProps(), queryFile.toString(), "./");

    s1 = KsqlIdentifierTestUtil.uniqueIdentifierName("S1");
    s2 = KsqlIdentifierTestUtil.uniqueIdentifierName("S2");
    t1 = KsqlIdentifierTestUtil.uniqueIdentifierName("T1");
  }

  @After
  public void tearDown() throws Exception {
    standalone.stop();
    standalone.join();
  }

  @Test
  public void shouldHandleJsonWithSchemas() {
    givenScript(""
        + "CREATE STREAM S (ORDERTIME BIGINT)"
        + "    WITH (kafka_topic='" + JSON_TOPIC + "', value_format='json');\n"
        + "\n"
        + "CREATE TABLE T (ORDERTIME BIGINT) "
        + "    WITH (kafka_topic='" + JSON_TOPIC + "', value_format='json', key='ORDERTIME');\n"
        + "\n"
        + "SET 'auto.offset.reset' = 'earliest';"
        + "\n"
        + "CREATE STREAM " + s1 + " AS SELECT * FROM S;\n"
        + "\n"
        + "INSERT INTO " + s1 + " SELECT * FROM S;\n"
        + "\n"
        + "CREATE TABLE " + t1 + " AS SELECT * FROM T;\n"
        + "\n"
        + "UNSET 'auto.offset.reset';"
        + "\n"
        + "CREATE STREAM " + s2 + " AS SELECT * FROM S;\n");

    // When:
    standalone.start();

    // Then:
    // CSAS and INSERT INTO both input into S1:
    TEST_HARNESS.verifyAvailableRows(s1, DATA_SIZE * 2, JSON, DATA_SCHEMA);
    // CTAS only into T1:
    TEST_HARNESS.verifyAvailableUniqueRows(t1, DATA_SIZE, JSON, DATA_SCHEMA);
    // S2 should be empty as 'auto.offset.reset' unset:
    TEST_HARNESS.verifyAvailableUniqueRows(s2, 0, JSON, DATA_SCHEMA);
  }

  @Test
  public void shouldHandleComments() {
    // Given:
    givenScript(""
        + "-- Single line comment\n"
        + ""
        + "/*\n"
        + "Multi-line comment\n"
        + " */\n"
        + ""
        + "SET 'auto.offset.reset' = 'earliest';"
        + ""
        + "CREATE STREAM S /*inline comment*/ (ID int)"
        + "    with (kafka_topic='" + JSON_TOPIC + "',value_format='json');\n"
        + "\n"
        + "CREATE STREAM " + s1 + "  AS SELECT * FROM S;");

    // When:
    standalone.start();

    // Then:
    TEST_HARNESS.verifyAvailableRows(s1, DATA_SIZE, JSON, DATA_SCHEMA);
  }

  private void givenScript(final String contents) {
    try {
      Files.write(queryFile, contents.getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new AssertionError("Failed to save query file", e);
    }
  }

  private static Map<String, String> defaultProps() {
    return ImmutableMap.of(
        CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, TEST_HARNESS.kafkaBootstrapServers()
    );
  }
}
