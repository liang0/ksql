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

package io.confluent.ksql;

import static io.confluent.ksql.util.KsqlExceptionMatcher.rawMessage;
import static io.confluent.ksql.util.KsqlExceptionMatcher.statementText;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableMap;
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.ksql.KsqlExecutionContext.ExecuteResult;
import io.confluent.ksql.function.InternalFunctionRegistry;
import io.confluent.ksql.metastore.MutableMetaStore;
import io.confluent.ksql.metastore.StructuredDataSource;
import io.confluent.ksql.parser.KsqlParser.ParsedStatement;
import io.confluent.ksql.parser.KsqlParser.PreparedStatement;
import io.confluent.ksql.parser.exception.ParseFailedException;
import io.confluent.ksql.parser.tree.CreateStream;
import io.confluent.ksql.parser.tree.CreateStreamAsSelect;
import io.confluent.ksql.parser.tree.CreateTable;
import io.confluent.ksql.parser.tree.DropTable;
import io.confluent.ksql.parser.tree.SetProperty;
import io.confluent.ksql.parser.tree.UnsetProperty;
import io.confluent.ksql.query.QueryId;
import io.confluent.ksql.serde.KsqlTopicSerDe;
import io.confluent.ksql.serde.json.KsqlJsonTopicSerDe;
import io.confluent.ksql.services.FakeKafkaTopicClient;
import io.confluent.ksql.services.ServiceContext;
import io.confluent.ksql.services.TestServiceContext;
import io.confluent.ksql.util.KsqlConfig;
import io.confluent.ksql.util.KsqlConstants;
import io.confluent.ksql.util.KsqlException;
import io.confluent.ksql.util.KsqlStatementException;
import io.confluent.ksql.util.MetaStoreFixture;
import io.confluent.ksql.util.PersistentQueryMetadata;
import io.confluent.ksql.util.QueryMetadata;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.avro.Schema;
import org.apache.avro.Schema.Type;
import org.apache.avro.SchemaBuilder;
import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.streams.StreamsConfig;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

@SuppressWarnings({"ConstantConditions", "SameParameterValue"})
@RunWith(MockitoJUnitRunner.class)
public class KsqlEngineTest {

  private static final KsqlConfig KSQL_CONFIG = new KsqlConfig(
      ImmutableMap.of(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092"));

  @Rule
  public final ExpectedException expectedException = ExpectedException.none();

  private MutableMetaStore metaStore;
  @Spy
  private final KsqlTopicSerDe jsonKsqlSerde = new KsqlJsonTopicSerDe();
  @Spy
  private final SchemaRegistryClient schemaRegistryClient = new MockSchemaRegistryClient();
  private final Supplier<SchemaRegistryClient> schemaRegistryClientFactory =
      () -> schemaRegistryClient;

  private KsqlEngine ksqlEngine;
  private ServiceContext serviceContext;
  @Spy
  private final FakeKafkaTopicClient topicClient = new FakeKafkaTopicClient();
  private KsqlExecutionContext sandbox;

  @Before
  public void setUp() {
    metaStore = MetaStoreFixture
        .getNewMetaStore(new InternalFunctionRegistry(), () -> jsonKsqlSerde);

    serviceContext = TestServiceContext.create(
        topicClient,
        schemaRegistryClientFactory
    );

    ksqlEngine = KsqlEngineTestUtil.createKsqlEngine(
        serviceContext,
        metaStore
    );

    sandbox = ksqlEngine.createSandbox();
  }

  @After
  public void closeEngine() {
    ksqlEngine.close();
    serviceContext.close();
  }

  @Test
  public void shouldCreatePersistentQueries() {
    final List<QueryMetadata> queries
        = KsqlEngineTestUtil.execute(ksqlEngine, "create table bar as select * from test2;" +
        "create table foo as select * from test2;", KSQL_CONFIG, Collections.emptyMap());

    assertThat(queries.size(), equalTo(2));
    final PersistentQueryMetadata queryOne = (PersistentQueryMetadata) queries.get(0);
    final PersistentQueryMetadata queryTwo = (PersistentQueryMetadata) queries.get(1);
    assertThat(queryOne.getEntity(), equalTo("BAR"));
    assertThat(queryTwo.getEntity(), equalTo("FOO"));
  }

  @Test
  public void shouldThrowOnTerminateAsNotExecutable() {
    // Given:
    final PersistentQueryMetadata query = (PersistentQueryMetadata) KsqlEngineTestUtil
        .execute(ksqlEngine,
        "create table bar as select * from test2;", KSQL_CONFIG, Collections.emptyMap()).get(0);

    expectedException.expect(KsqlStatementException.class);
    expectedException.expect(rawMessage(is("Statement not executable")));
    expectedException.expect(statementText(is("TERMINATE CTAS_BAR_0;")));

    // When:
    KsqlEngineTestUtil.execute(
        ksqlEngine, "TERMINATE " + query.getQueryId() + ";", KSQL_CONFIG, Collections.emptyMap());
  }

  @Test
  public void shouldExecuteInsertIntoStreamOnSandBox() {
    // Given:
    final List<ParsedStatement> statements = parse(
        "create stream bar as select * from orders;"
        + "insert into bar select * from orders;"
    );

    givenStatementAlreadyExecuted(statements.get(0));

    // When:
    final ExecuteResult result = sandbox
        .execute(sandbox.prepare(statements.get(1)), KSQL_CONFIG, Collections.emptyMap());

    // Then:
    assertThat(result.getQuery(), is(not(Optional.empty())));
  }

  @Test
  public void shouldThrowWhenExecutingInsertIntoTable() {
    // Given:
    KsqlEngineTestUtil.execute(
        ksqlEngine, "create table bar as select * from test2;", KSQL_CONFIG,
        Collections.emptyMap());

    final PreparedStatement<?> prepared = prepare(
        ksqlEngine.parse("insert into bar select * from test2;").get(0));

    expectedException.expect(KsqlStatementException.class);
    expectedException.expect(rawMessage(containsString(
        "INSERT INTO can only be used to insert into a stream. BAR is a table.")));
    expectedException.expect(statementText(is("insert into bar select * from test2;")));

    // When:
    ksqlEngine.execute(prepared, KSQL_CONFIG, Collections.emptyMap());
  }

  @Test
  public void shouldExecuteInsertIntoStream() {
    // Given:
    KsqlEngineTestUtil.execute(
        ksqlEngine, "create stream bar as select * from orders;", KSQL_CONFIG,
        Collections.emptyMap());

    // When:
    final List<QueryMetadata> queries = KsqlEngineTestUtil.execute(
        ksqlEngine, "insert into bar select * from orders;", KSQL_CONFIG, Collections.emptyMap());

    // Then:
    assertThat(queries, hasSize(1));
  }

  @Test
  public void shouldMaintainOrderOfReturnedQueries() {
    // When:
    final List<QueryMetadata> queries = KsqlEngineTestUtil.execute(ksqlEngine,
        "create stream foo as select * from orders;"
            + "create stream bar as select * from orders;",
        KSQL_CONFIG, Collections.emptyMap());

    // Then:
    assertThat(queries, hasSize(2));
    assertThat(queries.get(0).getStatementString(), containsString("create stream foo as"));
    assertThat(queries.get(1).getStatementString(), containsString("create stream bar as"));
  }

  @Test(expected = ParseFailedException.class)
  public void shouldFailToCreateQueryIfSelectingFromNonExistentEntity() {
    KsqlEngineTestUtil
        .execute(ksqlEngine, "select * from bar;", KSQL_CONFIG, Collections.emptyMap());
  }

  @Test(expected = ParseFailedException.class)
  public void shouldFailWhenSyntaxIsInvalid() {
    KsqlEngineTestUtil.execute(ksqlEngine, "blah;", KSQL_CONFIG, Collections.emptyMap());
  }

  @Test
  public void shouldUpdateReferentialIntegrityTableCorrectly() {
    KsqlEngineTestUtil.execute(ksqlEngine, "create table bar as select * from test2;" +
        "create table foo as select * from test2;", KSQL_CONFIG, Collections
        .emptyMap());

    assertThat(metaStore.getQueriesWithSource("TEST2"),
               equalTo(Utils.mkSet("CTAS_BAR_0", "CTAS_FOO_1")));
    assertThat(metaStore.getQueriesWithSink("BAR"), equalTo(Utils.mkSet("CTAS_BAR_0")));
    assertThat(metaStore.getQueriesWithSink("FOO"), equalTo(Utils.mkSet("CTAS_FOO_1")));
  }

  @Test
  public void shouldFailIfReferentialIntegrityIsViolated() {
    // Given:
    KsqlEngineTestUtil.execute(ksqlEngine, "create table bar as select * from test2;" +
            "create table foo as select * from test2;",
        KSQL_CONFIG, Collections.emptyMap());

    expectedException.expect(KsqlStatementException.class);
    expectedException.expect(rawMessage(is(
        "Cannot drop FOO.\n"
            + "The following queries read from this source: [].\n"
            + "The following queries write into this source: [CTAS_FOO_1].\n"
            + "You need to terminate them before dropping FOO.")));
    expectedException.expect(statementText(is("drop table foo;")));

    // When:
    KsqlEngineTestUtil.execute(ksqlEngine, "drop table foo;", KSQL_CONFIG, Collections.emptyMap());
  }

  @Test
  public void shouldFailDDLStatementIfTopicDoesNotExist() {
    // Given:
    final ParsedStatement stmt = parse(
        "CREATE STREAM S1_NOTEXIST (COL1 BIGINT, COL2 VARCHAR) "
            + "WITH  (KAFKA_TOPIC = 'S1_NOTEXIST', VALUE_FORMAT = 'JSON');").get(0);

    final PreparedStatement<?> prepared = prepare(stmt);

    // Then:
    expectedException.expect(KsqlStatementException.class);
    expectedException.expectMessage("Kafka topic does not exist: S1_NOTEXIST");

    // When:
    sandbox.execute(prepared, KSQL_CONFIG, Collections.emptyMap());
  }

  @Test
  public void shouldDropTableIfAllReferencedQueriesTerminated() {
    // Given:
    final QueryMetadata secondQuery = KsqlEngineTestUtil.execute(ksqlEngine,
        "create table bar as select * from test2;"
            + "create table foo as select * from test2;",
        KSQL_CONFIG, Collections.emptyMap()).get(1);

    secondQuery.close();

    // When:
    KsqlEngineTestUtil.execute(ksqlEngine, "drop table foo;", KSQL_CONFIG, Collections.emptyMap());

    // Then:
    assertThat(metaStore.getSource("foo"), nullValue());
  }

  @Test
  public void shouldEnforceTopicExistenceCorrectly() {
    serviceContext.getTopicClient().createTopic("s1_topic", 1, (short) 1);

    final String runScriptContent =
        "CREATE STREAM S1 (COL1 BIGINT) WITH  (KAFKA_TOPIC = 's1_topic', VALUE_FORMAT = 'JSON');\n"
        + "CREATE TABLE T1 AS SELECT COL1, count(*) FROM S1 GROUP BY COL1;\n"
        + "CREATE STREAM S2 (C1 BIGINT) WITH (KAFKA_TOPIC = 'T1', VALUE_FORMAT = 'JSON');\n";

    KsqlEngineTestUtil.execute(ksqlEngine, runScriptContent, KSQL_CONFIG, Collections.emptyMap());
    Assert.assertTrue(serviceContext.getTopicClient().isTopicExists("T1"));
  }

  @Test
  public void shouldNotEnforceTopicExistenceWhileParsing() {
    final String runScriptContent = "CREATE STREAM S1 (COL1 BIGINT, COL2 VARCHAR) "
        + "WITH  (KAFKA_TOPIC = 's1_topic', VALUE_FORMAT = 'JSON');\n"
        + "CREATE TABLE T1 AS SELECT COL1, count(*) FROM "
        + "S1 GROUP BY COL1;\n"
        + "CREATE STREAM S2 (C1 BIGINT, C2 BIGINT) "
        + "WITH (KAFKA_TOPIC = 'T1', VALUE_FORMAT = 'JSON');\n";

    final List<?> parsedStatements = ksqlEngine.parse(runScriptContent);

    assertThat(parsedStatements.size(), equalTo(3));
  }

  @Test
  public void shouldThrowFromSandBoxOnPrepareIfSourceTopicDoesNotExist() {
    // Given:
    final PreparedStatement<?> statement = prepare(parse(
        "CREATE STREAM S1 (COL1 BIGINT) "
            + "WITH (KAFKA_TOPIC = 'i_do_not_exist', VALUE_FORMAT = 'JSON');").get(0));

    // Expect:
    expectedException.expect(KsqlStatementException.class);
    expectedException.expect(rawMessage(is(
        "Kafka topic does not exist: i_do_not_exist")));
    expectedException.expect(statementText(is(
        "CREATE STREAM S1 (COL1 BIGINT)"
            + " WITH (KAFKA_TOPIC = 'i_do_not_exist', VALUE_FORMAT = 'JSON');")));

    // When:
    sandbox.execute(statement, KSQL_CONFIG, new HashMap<>());
  }

  @Test
  public void shouldThrowFromExecuteIfSourceTopicDoesNotExist() {
    // Given:
    final PreparedStatement<?> statement = prepare(parse(
        "CREATE STREAM S1 (COL1 BIGINT) "
            + "WITH (KAFKA_TOPIC = 'i_do_not_exist', VALUE_FORMAT = 'JSON');").get(0));

    // Expect:
    expectedException.expect(KsqlStatementException.class);
    expectedException.expect(rawMessage(is("Kafka topic does not exist: i_do_not_exist")));

    // When:
    ksqlEngine.execute(statement, KSQL_CONFIG, new HashMap<>());
  }

  @Test
  public void shouldThrowFromTryExecuteIfSinkTopicExistsWithWrongPartitionCount() {
    // Given:
    serviceContext.getTopicClient().createTopic("source", 1, (short) 1);
    serviceContext.getTopicClient().createTopic("sink", 2, (short) 1);

    final List<ParsedStatement> statements = parse(
        "CREATE STREAM S1 (C1 BIGINT) WITH (KAFKA_TOPIC='source', VALUE_FORMAT='JSON');\n"
            + "CREATE STREAM S2 WITH (KAFKA_TOPIC='sink') AS SELECT * FROM S1;\n");

    givenStatementAlreadyExecuted(statements.get(0));

    final PreparedStatement<?> prepared = prepare(statements.get(1));

    // Expect:
    expectedException.expect(KsqlStatementException.class);
    expectedException.expect(rawMessage(containsString(
        "A Kafka topic with the name 'sink' already exists, "
            + "with different partition/replica configuration than required")));

    // When:
    sandbox.execute(prepared, KSQL_CONFIG, new HashMap<>());
  }

  @Test
  public void shouldThrowFromExecuteIfSinkTopicExistsWithWrongPartitionCount() {
    // Given:
    final List<ParsedStatement> statements = parse(
        "CREATE STREAM S1 (C1 BIGINT) WITH (KAFKA_TOPIC='source', VALUE_FORMAT='JSON');\n"
            + "CREATE STREAM S2 WITH (KAFKA_TOPIC='sink') AS SELECT * FROM S1;\n");

    serviceContext.getTopicClient().createTopic("source", 1, (short) 1);
    serviceContext.getTopicClient().createTopic("sink", 2, (short) 1);

    ksqlEngine.execute(prepare(statements.get(0)), KSQL_CONFIG, new HashMap<>());

    final PreparedStatement<?> prepared = prepare(statements.get(1));

    // Expect:
    expectedException.expect(KsqlStatementException.class);
    expectedException.expect(rawMessage(containsString(
        "A Kafka topic with the name 'sink' already exists, "
            + "with different partition/replica configuration than required")));

    // When:
    ksqlEngine.execute(prepared, KSQL_CONFIG, new HashMap<>());
  }

  @Test
  public void shouldThrowFromTryExecuteIfSinkTopicExistsWithWrongReplicaCount() {
    // Given:
    final List<ParsedStatement> statements = parse(
        "CREATE STREAM S1 (C1 BIGINT) WITH (KAFKA_TOPIC='source', VALUE_FORMAT='JSON');\n"
            + "CREATE STREAM S2 WITH (KAFKA_TOPIC='sink') AS SELECT * FROM S1;\n");

    serviceContext.getTopicClient().createTopic("sink", 1, (short) 2);
    serviceContext.getTopicClient().createTopic("source", 1, (short) 3);

    givenStatementAlreadyExecuted(statements.get(0));

    final PreparedStatement<?> prepared = prepare(statements.get(1));

    // Expect:
    expectedException.expect(KsqlStatementException.class);
    expectedException.expect(rawMessage(containsString(
        "A Kafka topic with the name 'sink' already exists, "
            + "with different partition/replica configuration than required")));

    // When:
    sandbox.execute(prepared, KSQL_CONFIG, new HashMap<>());
  }

  @Test
  public void shouldThrowFromExecuteIfSinkTopicExistsWithWrongReplicaCount() {
    // Given:
    final List<ParsedStatement> statements = parse(
        "CREATE STREAM S1 (C1 BIGINT) WITH (KAFKA_TOPIC='source', VALUE_FORMAT='JSON');\n"
            + "CREATE STREAM S2 WITH (KAFKA_TOPIC='sink') AS SELECT * FROM S1;\n");

    serviceContext.getTopicClient().createTopic("source", 1, (short) 3);
    serviceContext.getTopicClient().createTopic("sink", 1, (short) 2);

    givenStatementAlreadyExecuted(statements.get(0));

    final PreparedStatement<?> prepared = prepare(statements.get(1));

    // Expect:
    expectedException.expect(KsqlStatementException.class);
    expectedException.expect(rawMessage(containsString(
        "A Kafka topic with the name 'sink' already exists, "
            + "with different partition/replica configuration than required")));

    // When:
    ksqlEngine.execute(prepared, KSQL_CONFIG, new HashMap<>());
  }

  @Test
  public void shouldHandleCommandsSpreadOverMultipleLines() {
    final String runScriptContent = "CREATE STREAM S1 \n"
        + "(COL1 BIGINT, COL2 VARCHAR)\n"
        + " WITH \n"
        + "(KAFKA_TOPIC = 's1_topic', VALUE_FORMAT = 'JSON');\n";

    final List<?> parsedStatements = ksqlEngine.parse(runScriptContent);

    assertThat(parsedStatements, hasSize(1));
  }

  @Test
  public void shouldCleanupSchemaAndTopicForStream() throws Exception {
    // Given:
    final QueryMetadata query = KsqlEngineTestUtil.execute(ksqlEngine,
        "create stream bar with (value_format = 'avro') as select * from test1;",
        KSQL_CONFIG, Collections.emptyMap()).get(0);

    query.close();

    final Schema schema = SchemaBuilder
        .record("Test").fields()
        .name("clientHash").type().fixed("MD5").size(16).noDefault()
        .endRecord();

    schemaRegistryClient.register("BAR-value", schema);

    // When:
    KsqlEngineTestUtil
        .execute(ksqlEngine, "DROP STREAM bar DELETE TOPIC;", KSQL_CONFIG, Collections.emptyMap());

    // Then:
    assertThat(serviceContext.getTopicClient().isTopicExists("BAR"), equalTo(false));
    assertThat(schemaRegistryClient.getAllSubjects(), not(hasItem("BAR-value")));
  }

  @Test
  public void shouldCleanupSchemaAndTopicForTable() throws Exception {
    // Given:
    final QueryMetadata query = KsqlEngineTestUtil.execute(ksqlEngine,
        "create table bar with (value_format = 'avro') as select * from test2;",
        KSQL_CONFIG, Collections.emptyMap()).get(0);

    query.close();

    final Schema schema = SchemaBuilder
        .record("Test").fields()
        .name("clientHash").type().fixed("MD5").size(16).noDefault()
        .endRecord();

    schemaRegistryClient.register("BAR-value", schema);

    // When:
    KsqlEngineTestUtil
        .execute(ksqlEngine, "DROP TABLE bar DELETE TOPIC;", KSQL_CONFIG, Collections.emptyMap());

    // Then:
    assertThat(serviceContext.getTopicClient().isTopicExists("BAR"), equalTo(false));
    assertThat(schemaRegistryClient.getAllSubjects(), not(hasItem("BAR-value")));
  }

  @Test
  public void shouldNotDeleteSchemaNorTopicForStream() throws Exception {
    // Given:
    final QueryMetadata query = KsqlEngineTestUtil.execute(ksqlEngine,
        "create stream bar with (value_format = 'avro') as select * from test1;"
        + "create stream foo as select * from test1;",
        KSQL_CONFIG, Collections.emptyMap()).get(0);

    query.close();

    final Schema schema = SchemaBuilder
        .record("Test").fields()
        .name("clientHash").type().fixed("MD5").size(16).noDefault()
        .endRecord();

    schemaRegistryClient.register("BAR-value", schema);

    // When:
    KsqlEngineTestUtil.execute(ksqlEngine, "DROP STREAM bar;", KSQL_CONFIG, Collections.emptyMap());

    // Then:
    assertThat(serviceContext.getTopicClient().isTopicExists("BAR"), equalTo(true));
    assertThat(schemaRegistryClient.getAllSubjects(), hasItem("BAR-value"));
  }

  @Test
  public void shouldInferSchemaIfNotPresent() {
    final Schema schema = SchemaBuilder
        .record("Test").fields()
        .name("field").type().intType().noDefault()
        .endRecord();
    givenTopicWithSchema("bar", schema);

    KsqlEngineTestUtil.execute(ksqlEngine,
        "create stream bar with (value_format='avro', kafka_topic='bar');",
        KSQL_CONFIG,
        Collections.emptyMap());

    final StructuredDataSource source = metaStore.getSource("BAR");
    final org.apache.kafka.connect.data.Schema ksqlSchema = source.getSchema();
    assertThat(ksqlSchema.fields().size(), equalTo(3));
    assertThat(ksqlSchema.fields().get(2).name(), equalTo("FIELD"));
    assertThat(
        ksqlSchema.fields().get(2).schema(),
        equalTo(org.apache.kafka.connect.data.Schema.OPTIONAL_INT32_SCHEMA));
    assertThat(source.getSqlExpression(), containsString("(FIELD INTEGER)"));
  }

  @Test
  public void shouldFailIfAvroSchemaNotEvolvable() {
    // Given:
    givenTopicWithSchema("T", Schema.create(Type.INT));

    expectedException.expect(KsqlStatementException.class);
    expectedException.expect(rawMessage(containsString(
        "Cannot register avro schema for T as the schema registry rejected it, "
            + "(maybe schema evolution issues?)")));
    expectedException.expect(statementText(is(
        "CREATE TABLE T WITH(VALUE_FORMAT='AVRO') AS SELECT * FROM TEST2;")));

    // When:
    KsqlEngineTestUtil.execute(ksqlEngine,
        "CREATE TABLE T WITH(VALUE_FORMAT='AVRO') AS SELECT * FROM TEST2;",
        KSQL_CONFIG,
        Collections.emptyMap());
  }

  @Test
  public void shouldNotFailIfAvroSchemaEvolvable() {
    // Given:
    final Schema evolvableSchema = SchemaBuilder
        .record("Test").fields()
        .nullableInt("f1", 1)
        .endRecord();

    givenTopicWithSchema("T", evolvableSchema);

    // When:
    KsqlEngineTestUtil.execute(ksqlEngine,
        "CREATE TABLE T WITH(VALUE_FORMAT='AVRO') AS SELECT * FROM TEST2;",
        KSQL_CONFIG,
        Collections.emptyMap());

    // Then:
    assertThat(metaStore.getSource("T"), is(notNullValue()));
  }

  @Test
  public void shouldNotDeleteSchemaNorTopicForTable() throws Exception {
    // Given:
    final QueryMetadata query = KsqlEngineTestUtil.execute(ksqlEngine,
        "create table bar with (value_format = 'avro') as select * from test2;",
        KSQL_CONFIG, Collections.emptyMap()).get(0);

    query.close();

    final Schema schema = SchemaBuilder
        .record("Test").fields()
        .name("clientHash").type().fixed("MD5").size(16).noDefault()
        .endRecord();

    schemaRegistryClient.register("BAR-value", schema);

    // When:
    KsqlEngineTestUtil.execute(ksqlEngine, "DROP TABLE bar;", KSQL_CONFIG, Collections.emptyMap());

    // Then:
    assertThat(serviceContext.getTopicClient().isTopicExists("BAR"), equalTo(true));
    assertThat(schemaRegistryClient.getAllSubjects(), hasItem("BAR-value"));
  }

  @Test
  public void shouldCleanUpInternalTopicsOnClose() {
    // Given:
    final QueryMetadata query = KsqlEngineTestUtil.execute(ksqlEngine,
        "select * from test1;",
        KSQL_CONFIG, Collections.emptyMap()).get(0);

    query.start();

    // When:
    query.close();

    // Then:
    verify(topicClient).deleteInternalTopics(query.getQueryApplicationId());
  }

  @Test
  public void shouldNotCleanUpInternalTopicsOnCloseIfQueryNeverStarted() {
    // Given:
    final QueryMetadata query = KsqlEngineTestUtil.execute(ksqlEngine,
        "create stream s1 with (value_format = 'avro') as select * from test1;",
        KSQL_CONFIG, Collections.emptyMap()).get(0);

    // When:
    query.close();

    // Then:
    verify(topicClient, never()).deleteInternalTopics(any());
  }

  @Test
  public void shouldRemovePersistentQueryFromEngineWhenClosed() {
    // Given:
    final int startingLiveQueries = ksqlEngine.numberOfLiveQueries();
    final int startingPersistentQueries = ksqlEngine.numberOfPersistentQueries();

    final QueryMetadata query = KsqlEngineTestUtil.execute(ksqlEngine,
        "create stream s1 with (value_format = 'avro') as select * from test1;",
        KSQL_CONFIG, Collections.emptyMap()).get(0);


    // When:
    query.close();

    // Then:
    assertThat(ksqlEngine.getPersistentQuery(getQueryId(query)), is(Optional.empty()));
    assertThat(ksqlEngine.numberOfLiveQueries(), is(startingLiveQueries));
    assertThat(ksqlEngine.numberOfPersistentQueries(), is(startingPersistentQueries));
  }

  @Test
  public void shouldRemoveTransientQueryFromEngineWhenClosed() {
    // Given:
    final int startingLiveQueries = ksqlEngine.numberOfLiveQueries();

    final QueryMetadata query = KsqlEngineTestUtil.execute(ksqlEngine,
        "select * from test1;",
        KSQL_CONFIG, Collections.emptyMap()).get(0);

    // When:
    query.close();

    // Then:
    assertThat(ksqlEngine.numberOfLiveQueries(), is(startingLiveQueries));
  }

  @Test
  public void shouldUseSerdeSupplierToBuildQueries() {
    // When:
    KsqlEngineTestUtil.execute(ksqlEngine,
        "create table bar as select * from test2;", KSQL_CONFIG, Collections.emptyMap());

    // Then:
    verify(jsonKsqlSerde, atLeastOnce()).getGenericRowSerde(
        any(), any(), anyBoolean(), eq(schemaRegistryClientFactory), any(), any()
    );
  }

  @SuppressWarnings("unchecked")
  @Test
  public void shouldHandleMultipleStatements() {
    // Given:
    final String sql = ""
        + "-- single line comment\n"
        + "/*\n"
        + "   Multi-line comment\n"
        + "*/\n"
        + "CREATE STREAM S0 (a INT, b VARCHAR) "
        + "      WITH (kafka_topic='s0_topic', value_format='DELIMITED');\n"
        + "\n"
        + "SET 'auto.offset.reset'='earliest';\n"
        + "\n"
        + "CREATE TABLE T1 (f0 BIGINT, f1 DOUBLE) "
        + "     WITH (kafka_topic='t1_topic', value_format='JSON', key = 'f0');\n"
        + "\n"
        + "CREATE STREAM S1 AS SELECT * FROM S0;\n"
        + "\n"
        + "UNSET 'auto.offset.reset';\n"
        + "\n"
        + "CREATE STREAM S2 AS SELECT * FROM S0;\n"
        + "\n"
        + "DROP TABLE T1;";

    givenTopicsExist("s0_topic", "t1_topic");

    final List<QueryMetadata> queries = new ArrayList<>();

    // When:
    final List<PreparedStatement<?>> preparedStatements = ksqlEngine.parse(sql).stream()
        .map(stmt ->
        {
          final PreparedStatement<?> prepared = ksqlEngine.prepare(stmt);
          final ExecuteResult result = ksqlEngine.execute(prepared, KSQL_CONFIG, new HashMap<>());
          result.getQuery().ifPresent(queries::add);
          return prepared;
        })
        .collect(Collectors.toList());

    // Then:
    final List<?> statements = preparedStatements.stream()
        .map(PreparedStatement::getStatement)
        .collect(Collectors.toList());

    assertThat(statements, contains(
        instanceOf(CreateStream.class),
        instanceOf(SetProperty.class),
        instanceOf(CreateTable.class),
        instanceOf(CreateStreamAsSelect.class),
        instanceOf(UnsetProperty.class),
        instanceOf(CreateStreamAsSelect.class),
        instanceOf(DropTable.class)
    ));

    assertThat(queries, hasSize(2));
  }

  @Test
  public void shouldSetPropertyInRunScript() {
    final Map<String, Object> overriddenProperties = new HashMap<>();

    KsqlEngineTestUtil.execute(ksqlEngine,
        "SET 'auto.offset.reset' = 'earliest';",
        KSQL_CONFIG, overriddenProperties);

    assertThat(overriddenProperties.get("auto.offset.reset"), equalTo("earliest"));
  }

  @Test
  public void shouldUnsetPropertyInRunScript() {
    final Map<String, Object> overriddenProperties = new HashMap<>();

    KsqlEngineTestUtil.execute(ksqlEngine,
        "SET 'auto.offset.reset' = 'earliest';"
            + "UNSET 'auto.offset.reset';",
        KSQL_CONFIG, overriddenProperties);

    assertThat(overriddenProperties.keySet(), not(hasItem("auto.offset.reset")));
  }

  @Test
  public void shouldNotThrowWhenPreparingDuplicateTable() {
    // Given:
    final List<ParsedStatement> parsed = ksqlEngine.parse(
        "CREATE TABLE FOO AS SELECT * FROM TEST2; "
            + "CREATE TABLE FOO WITH (KAFKA_TOPIC='BAR') AS SELECT * FROM TEST2;");

    givenStatementAlreadyExecuted(parsed.get(0));

    // When:
    ksqlEngine.prepare(parsed.get(1));

    // Then: no exception thrown
  }

  @Test
  public void shouldThrowWhenExecutingDuplicateTable() {
    // Given:
    final List<ParsedStatement> parsed = ksqlEngine.parse(
        "CREATE TABLE FOO AS SELECT * FROM TEST2; "
            + "CREATE TABLE FOO WITH (KAFKA_TOPIC='BAR') AS SELECT * FROM TEST2;");

    givenStatementAlreadyExecuted(parsed.get(0));

    final PreparedStatement<?> prepared = prepare(parsed.get(1));

    expectedException.expect(KsqlStatementException.class);
    expectedException.expect(rawMessage(is(
        "Cannot add the new data source. "
            + "Another data source with the same name already exists: KsqlTable name:FOO")));
    expectedException.expect(statementText(is(
        "CREATE TABLE FOO WITH (KAFKA_TOPIC='BAR') AS SELECT * FROM TEST2;")));

    // When:
    ksqlEngine.execute(prepared, KSQL_CONFIG, Collections.emptyMap());
  }

  @Test
  public void shouldThrowWhenPreparingUnknownSource() {
    // Given:
    final ParsedStatement stmt = ksqlEngine.parse(
        "CREATE STREAM FOO AS SELECT * FROM UNKNOWN;").get(0);

    // Then:
    expectedException.expect(KsqlStatementException.class);
    expectedException.expect(rawMessage(is(
        "Failed to prepare statement: UNKNOWN does not exist.")));
    expectedException.expect(statementText(is(
        "CREATE STREAM FOO AS SELECT * FROM UNKNOWN;")));

    // When:
    ksqlEngine.prepare(stmt);
  }

  @Test
  public void shouldNotThrowWhenPreparingDuplicateStream() {
    // Given:
    final ParsedStatement stmt = ksqlEngine.parse(
        "CREATE STREAM FOO AS SELECT * FROM ORDERS; "
            + "CREATE STREAM FOO WITH (KAFKA_TOPIC='BAR') AS SELECT * FROM ORDERS;").get(0);

    // When:
    ksqlEngine.prepare(stmt);

    // Then: No exception thrown.
  }

  @Test
  public void shouldThrowWhenExecutingDuplicateStream() {
    // Given:
    final List<ParsedStatement> parsed = ksqlEngine.parse(
        "CREATE STREAM FOO AS SELECT * FROM ORDERS; "
            + "CREATE STREAM FOO WITH (KAFKA_TOPIC='BAR') AS SELECT * FROM ORDERS;");

    givenStatementAlreadyExecuted(parsed.get(0));

    final PreparedStatement<?> prepared = ksqlEngine.prepare(parsed.get(1));

    // Then:
    expectedException.expect(KsqlStatementException.class);
    expectedException.expect(rawMessage(is(
        "Cannot add the new data source. "
            + "Another data source with the same name already exists: KsqlStream name:FOO")));
    expectedException.expect(statementText(is(
        "CREATE STREAM FOO WITH (KAFKA_TOPIC='BAR') AS SELECT * FROM ORDERS;")));

    // When:
    ksqlEngine.execute(prepared, KSQL_CONFIG, Collections.emptyMap());
  }

  @Test
  public void shouldThrowWhenExecutingQueriesIfCsasCreatesTable() {
    // Given:
    expectedException.expect(KsqlStatementException.class);
    expectedException.expect(rawMessage(containsString(
        "Invalid result type. Your SELECT query produces a TABLE. "
            + "Please use CREATE TABLE AS SELECT statement instead.")));
    expectedException.expect(statementText(is(
        "CREATE STREAM FOO AS SELECT COUNT(ORDERID) FROM ORDERS GROUP BY ORDERID;")));

    // When:
    KsqlEngineTestUtil.execute(ksqlEngine,
        "CREATE STREAM FOO AS SELECT COUNT(ORDERID) FROM ORDERS GROUP BY ORDERID;",
        KSQL_CONFIG, Collections.emptyMap());
  }

  @Test
  public void shouldThrowWhenExecutingQueriesIfCtasCreatesStream() {
    // Given:
    expectedException.expect(KsqlStatementException.class);
    expectedException.expect(rawMessage(containsString(
        "Invalid result type. Your SELECT query produces a STREAM. "
            + "Please use CREATE STREAM AS SELECT statement instead.")));
    expectedException.expect(statementText(is(
        "CREATE TABLE FOO AS SELECT * FROM ORDERS;")));

    // When:
    KsqlEngineTestUtil.execute(ksqlEngine,
        "CREATE TABLE FOO AS SELECT * FROM ORDERS;",
        KSQL_CONFIG, Collections.emptyMap());
  }

  @Test
  public void shouldThrowWhenTryExecuteCsasThatCreatesTable() {
    // Given:
    final PreparedStatement<?> statement = prepare(parse(
        "CREATE STREAM FOO AS SELECT COUNT(ORDERID) FROM ORDERS GROUP BY ORDERID;").get(0));

    expectedException.expect(KsqlStatementException.class);
    expectedException.expect(rawMessage(containsString(
        "Invalid result type. Your SELECT query produces a TABLE. "
            + "Please use CREATE TABLE AS SELECT statement instead.")));
    expectedException.expect(statementText(is(
        "CREATE STREAM FOO AS SELECT COUNT(ORDERID) FROM ORDERS GROUP BY ORDERID;")));

    // When:
    sandbox.execute(statement, KSQL_CONFIG, Collections.emptyMap());
  }

  @Test
  public void shouldThrowWhenTryExecuteCtasThatCreatesStream() {
    // Given:
    final PreparedStatement<?> statement = prepare(parse(
        "CREATE TABLE FOO AS SELECT * FROM ORDERS;").get(0));

    expectedException.expect(KsqlStatementException.class);
    expectedException.expect(statementText(is("CREATE TABLE FOO AS SELECT * FROM ORDERS;")));
    expectedException.expect(rawMessage(is(
        "Invalid result type. Your SELECT query produces a STREAM. "
            + "Please use CREATE STREAM AS SELECT statement instead.")));

    // When:
    sandbox.execute(statement, KSQL_CONFIG, Collections.emptyMap());
  }

  @Test
  public void shouldThrowIfStatementMissingTopicConfig() {
    final List<ParsedStatement> parsed = parse(
        "CREATE TABLE FOO (viewtime BIGINT, pageid VARCHAR) WITH (VALUE_FORMAT='AVRO');"
            + "CREATE STREAM FOO (viewtime BIGINT, pageid VARCHAR) WITH (VALUE_FORMAT='AVRO');"
            + "CREATE TABLE FOO (viewtime BIGINT, pageid VARCHAR) WITH (VALUE_FORMAT='JSON');"
            + "CREATE STREAM FOO (viewtime BIGINT, pageid VARCHAR) WITH (VALUE_FORMAT='JSON');"
    );

    for (ParsedStatement statement : parsed) {
      final PreparedStatement<?> prepared = ksqlEngine.prepare(statement);

      try {
        ksqlEngine.execute(prepared, KSQL_CONFIG, Collections.emptyMap());
        Assert.fail();
      } catch (final KsqlException e) {
        assertThat(e.getMessage(), containsString(
            "Corresponding Kafka topic (KAFKA_TOPIC) should be set in WITH clause."));
      }
    }
  }

  @Test
  public void shouldThrowOnNoneExecutableDdlStatement() {
    // Given:
    expectedException.expect(KsqlStatementException.class);
    expectedException.expect(rawMessage(is("Statement not executable")));
    expectedException.expect(statementText(is("SHOW STREAMS;")));

    // When:
    KsqlEngineTestUtil.execute(ksqlEngine, "SHOW STREAMS;", KSQL_CONFIG, Collections.emptyMap());
  }

  @Test
  public void shouldNotUpdateMetaStoreDuringTryExecute() {
    // Given:
    final int numberOfLiveQueries = ksqlEngine.numberOfLiveQueries();
    final int numPersistentQueries = ksqlEngine.numberOfPersistentQueries();

    final List<ParsedStatement> statements = parse(
        "SET 'auto.offset.reset' = 'earliest';"
            + "CREATE STREAM S1 (COL1 BIGINT) WITH (KAFKA_TOPIC = 's1_topic', VALUE_FORMAT = 'JSON');"
            + "CREATE TABLE BAR AS SELECT * FROM TEST2;"
            + "CREATE TABLE FOO AS SELECT * FROM TEST2;"
            + "DROP TABLE TEST3;");

    topicClient.preconditionTopicExists("s1_topic", 1, (short) 1, Collections.emptyMap());

    // When:
    statements
        .forEach(stmt -> sandbox.execute(sandbox.prepare(stmt), KSQL_CONFIG, new HashMap<>()));

    // Then:
    assertThat(metaStore.getSource("TEST3"), is(notNullValue()));
    assertThat(metaStore.getQueriesWithSource("TEST2"), is(empty()));
    assertThat(metaStore.getSource("BAR"), is(nullValue()));
    assertThat(metaStore.getSource("FOO"), is(nullValue()));
    assertThat("live", ksqlEngine.numberOfLiveQueries(), is(numberOfLiveQueries));
    assertThat("peristent", ksqlEngine.numberOfPersistentQueries(), is(numPersistentQueries));
  }

  @Test
  public void shouldNotCreateAnyTopicsDuringTryExecute() {
    // Given:
    topicClient.preconditionTopicExists("s1_topic", 1, (short) 1, Collections.emptyMap());

    final List<ParsedStatement> statements = parse(
        "CREATE STREAM S1 (COL1 BIGINT) WITH (KAFKA_TOPIC = 's1_topic', VALUE_FORMAT = 'JSON');"
            + "CREATE TABLE BAR AS SELECT * FROM TEST2;"
            + "CREATE TABLE FOO AS SELECT * FROM TEST2;"
            + "DROP TABLE TEST3;");

    // When:
    statements.forEach(
        stmt -> sandbox.execute(sandbox.prepare(stmt), KSQL_CONFIG, Collections.emptyMap()));

    // Then:
    assertThat("no topics should be created during a tryExecute call",
        topicClient.createdTopics().keySet(), is(empty()));
  }

  @Test
  public void shouldNotIncrementQueryIdCounterDuringTryExecute() {
    // Given:
    final String sql = "create table foo as select * from test2;";
    final PreparedStatement<?> statement = prepare(parse(sql).get(0));

    // When:
    sandbox.execute(statement, KSQL_CONFIG, Collections.emptyMap());

    // Then:
    final List<QueryMetadata> queries = KsqlEngineTestUtil
        .execute(ksqlEngine, sql, KSQL_CONFIG, Collections.emptyMap());
    assertThat("query id of actual execute should not be affected by previous tryExecute",
        ((PersistentQueryMetadata)queries.get(0)).getQueryId(), is(new QueryId("CTAS_FOO_0")));
  }

  @Test
  public void shouldNotRegisterAnySchemasDuringSandboxExecute() throws Exception {
    // Given:
    final List<ParsedStatement> statements = parse(
        "create table foo WITH(VALUE_FORMAT='AVRO') as select * from test2;"
        + "create stream foo2 WITH(VALUE_FORMAT='AVRO') as select * from orders;");

    givenStatementAlreadyExecuted(statements.get(0));

    final PreparedStatement<?> prepared = prepare(statements.get(1));

    // When:
    sandbox.execute(prepared, KSQL_CONFIG, Collections.emptyMap());

    // Then:
    verify(schemaRegistryClient, never()).register(any(), any());
  }

  @Test
  public void shouldOnlyUpdateSandboxOnQueryClose() {
    // Given:
    givenSqlAlreadyExecuted("create table bar as select * from test2;");

    final QueryId queryId = ksqlEngine.getPersistentQueries()
        .get(0).getQueryId();

    final PersistentQueryMetadata sandBoxQuery = sandbox.getPersistentQuery(queryId)
        .get();

    // When:
    sandBoxQuery.close();

    // Then:
    assertThat("main engine should not be updated",
        ksqlEngine.getPersistentQuery(queryId), is(not(Optional.empty())));

    assertThat("sand box should be updated",
        sandbox.getPersistentQuery(queryId), is(Optional.empty()));
  }

  @Test
  public void shouldRegisterPersistentQueriesOnlyInSandbox() {
    // Given:
    final PreparedStatement<?> prepared = prepare(parse(
        "create table bar as select * from test2;").get(0));

    // When:
    final ExecuteResult result = sandbox.execute(prepared, KSQL_CONFIG, Collections.emptyMap());

    // Then:
    assertThat(result.getQuery(), is(not(Optional.empty())));
    assertThat(sandbox.getPersistentQuery(getQueryId(result.getQuery().get())), is(not(Optional.empty())));
    assertThat(ksqlEngine.getPersistentQuery(getQueryId(result.getQuery().get())), is(Optional.empty()));
  }

  @Test
  public void shouldExecuteDdlStatement() {
    // Given:
    final PreparedStatement<?> statement =
        prepare(parse("SET 'auto.offset.reset' = 'earliest';").get(0));

    // When:
    final ExecuteResult result = sandbox.execute(statement, KSQL_CONFIG, new HashMap<>());

    // Then:
    assertThat(result.getCommandResult(),
        is(Optional.of("property:auto.offset.reset set to earliest")));
  }

  @Test
  public void shouldBeAbleToParseInvalidThings() {
    // Given:
    // No Stream called 'I_DO_NOT_EXIST' exists

    // When:
    final List<ParsedStatement> parsed = ksqlEngine
        .parse("CREATE STREAM FOO AS SELECT * FROM I_DO_NOT_EXIST;");

    // Then:
    assertThat(parsed, hasSize(1));
  }

  @Test
  public void shouldThrowOnPrepareIfSourcesDoNotExist() {
    // Given:
    final ParsedStatement parsed = ksqlEngine
        .parse("CREATE STREAM FOO AS SELECT * FROM I_DO_NOT_EXIST;")
        .get(0);

    // Then:
    expectedException.expect(KsqlException.class);
    expectedException.expectMessage(
        "Failed to prepare statement: I_DO_NOT_EXIST does not exist");

    // When:
    ksqlEngine.prepare(parsed);
  }

  @Test
  public void shouldBeAbleToPrepareTerminateAndDrop() {
    // Given:
    givenSqlAlreadyExecuted("CREATE STREAM FOO AS SELECT * FROM TEST1;");

    final List<ParsedStatement> parsed = ksqlEngine.parse(
        "TERMINATE CSAS_FOO_0;"
            + "DROP STREAM FOO;");

    // When:
    parsed.forEach(ksqlEngine::prepare);

    // Then: did not throw.
  }

  private void givenTopicsExist(final String... topics) {
    givenTopicsExist(1, topics);
  }

  private void givenTopicsExist(final int partitionCount, final String... topics) {
    Arrays.stream(topics)
        .forEach(topic -> topicClient.createTopic(topic, partitionCount, (short) 1));
  }

  private List<ParsedStatement> parse(final String sql) {
    return ksqlEngine.parse(sql);
  }

  private PreparedStatement<?> prepare(final ParsedStatement stmt) {
    return ksqlEngine.prepare(stmt);
  }

  private void givenTopicWithSchema(final String topicName, final Schema schema) {
    try {
      givenTopicsExist(4, topicName);
      schemaRegistryClient.register(topicName + KsqlConstants.SCHEMA_REGISTRY_VALUE_SUFFIX, schema);
    } catch (final Exception e) {
      fail("invalid test:" + e.getMessage());
    }
  }

  private static QueryId getQueryId(final QueryMetadata query) {
    return ((PersistentQueryMetadata)query).getQueryId();
  }

  private void givenStatementAlreadyExecuted(
      final ParsedStatement statement
  ) {
    ksqlEngine.execute(ksqlEngine.prepare(statement), KSQL_CONFIG, new HashMap<>());
    sandbox = ksqlEngine.createSandbox();
  }

  private void givenSqlAlreadyExecuted(final String sql) {
    parse(sql).forEach(stmt ->
        ksqlEngine.execute(ksqlEngine.prepare(stmt), KSQL_CONFIG, new HashMap<>()));

    sandbox = ksqlEngine.createSandbox();
  }
}
