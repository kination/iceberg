/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.spark;

import static org.apache.spark.sql.functions.col;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.ContentFile;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.ManifestFiles;
import org.apache.iceberg.ManifestWriter;
import org.apache.iceberg.MetadataTableType;
import org.apache.iceberg.MetadataTableUtils;
import org.apache.iceberg.MetricsConfig;
import org.apache.iceberg.PartitionField;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.data.TableMigrationUtil;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.hadoop.HadoopFileIO;
import org.apache.iceberg.hadoop.SerializableConfiguration;
import org.apache.iceberg.hadoop.Util;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.io.SupportsBulkOperations;
import org.apache.iceberg.mapping.NameMapping;
import org.apache.iceberg.mapping.NameMappingParser;
import org.apache.iceberg.relocated.com.google.common.base.Joiner;
import org.apache.iceberg.relocated.com.google.common.base.MoreObjects;
import org.apache.iceberg.relocated.com.google.common.base.Objects;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.base.Splitter;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;
import org.apache.iceberg.spark.source.SparkTable;
import org.apache.iceberg.util.PropertyUtil;
import org.apache.iceberg.util.Tasks;
import org.apache.iceberg.util.ThreadPools;
import org.apache.spark.TaskContext;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.api.java.function.MapFunction;
import org.apache.spark.api.java.function.MapPartitionsFunction;
import org.apache.spark.sql.AnalysisException;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Encoders;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.catalyst.TableIdentifier;
import org.apache.spark.sql.catalyst.analysis.NoSuchDatabaseException;
import org.apache.spark.sql.catalyst.analysis.NoSuchTableException;
import org.apache.spark.sql.catalyst.analysis.UnresolvedAttribute;
import org.apache.spark.sql.catalyst.catalog.CatalogTable;
import org.apache.spark.sql.catalyst.catalog.CatalogTablePartition;
import org.apache.spark.sql.catalyst.catalog.SessionCatalog;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.NamedExpression;
import org.apache.spark.sql.catalyst.parser.ParseException;
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan;
import org.apache.spark.sql.execution.datasources.v2.DataSourceV2Relation;
import org.apache.spark.sql.util.CaseInsensitiveStringMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Function2;
import scala.Option;
import scala.Some;
import scala.Tuple2;
import scala.collection.JavaConverters;
import scala.collection.immutable.Map$;
import scala.collection.immutable.Seq;
import scala.collection.mutable.Builder;
import scala.runtime.AbstractPartialFunction;

/**
 * Java version of the original SparkTableUtil.scala
 * https://github.com/apache/iceberg/blob/apache-iceberg-0.8.0-incubating/spark/src/main/scala/org/apache/iceberg/spark/SparkTableUtil.scala
 */
public class SparkTableUtil {
  private static final Logger LOG = LoggerFactory.getLogger(SparkTableUtil.class);

  private static final String DUPLICATE_FILE_MESSAGE =
      "Cannot complete import because data files "
          + "to be imported already exist within the target table: %s.  "
          + "This is disabled by default as Iceberg is not designed for multiple references to the same file"
          + " within the same table.  If you are sure, you may set 'check_duplicate_files' to false to force the import.";

  private SparkTableUtil() {}

  /**
   * Returns a DataFrame with a row for each partition in the table.
   *
   * <p>The DataFrame has 3 columns, partition key (a=1/b=2), partition location, and format (avro
   * or parquet).
   *
   * @param spark a Spark session
   * @param table a table name and (optional) database
   * @return a DataFrame of the table's partitions
   */
  public static Dataset<Row> partitionDF(SparkSession spark, String table) {
    List<SparkPartition> partitions = getPartitions(spark, table);
    return spark
        .createDataFrame(partitions, SparkPartition.class)
        .toDF("partition", "uri", "format");
  }

  /**
   * Returns a DataFrame with a row for each partition that matches the specified 'expression'.
   *
   * @param spark a Spark session.
   * @param table name of the table.
   * @param expression The expression whose matching partitions are returned.
   * @return a DataFrame of the table partitions.
   */
  public static Dataset<Row> partitionDFByFilter(
      SparkSession spark, String table, String expression) {
    List<SparkPartition> partitions = getPartitionsByFilter(spark, table, expression);
    return spark
        .createDataFrame(partitions, SparkPartition.class)
        .toDF("partition", "uri", "format");
  }

  /**
   * Returns all partitions in the table.
   *
   * @param spark a Spark session
   * @param table a table name and (optional) database
   * @return all table's partitions
   */
  public static List<SparkPartition> getPartitions(SparkSession spark, String table) {
    try {
      TableIdentifier tableIdent = spark.sessionState().sqlParser().parseTableIdentifier(table);
      return getPartitions(spark, tableIdent, null);
    } catch (ParseException e) {
      throw SparkExceptionUtil.toUncheckedException(
          e, "Unable to parse table identifier: %s", table);
    }
  }

  /**
   * Returns all partitions in the table.
   *
   * @param spark a Spark session
   * @param tableIdent a table identifier
   * @param partitionFilter partition filter, or null if no filter
   * @return all table's partitions
   */
  public static List<SparkPartition> getPartitions(
      SparkSession spark, TableIdentifier tableIdent, Map<String, String> partitionFilter) {
    try {
      SessionCatalog catalog = spark.sessionState().catalog();
      CatalogTable catalogTable = catalog.getTableMetadata(tableIdent);

      Option<scala.collection.immutable.Map<String, String>> scalaPartitionFilter;
      if (partitionFilter != null && !partitionFilter.isEmpty()) {
        Builder<Tuple2<String, String>, scala.collection.immutable.Map<String, String>> builder =
            Map$.MODULE$.newBuilder();
        partitionFilter.forEach((key, value) -> builder.$plus$eq(Tuple2.apply(key, value)));
        scalaPartitionFilter = Option.apply(builder.result());
      } else {
        scalaPartitionFilter = Option.empty();
      }
      Seq<CatalogTablePartition> partitions =
          catalog.listPartitions(tableIdent, scalaPartitionFilter).toIndexedSeq();
      return JavaConverters.seqAsJavaListConverter(partitions).asJava().stream()
          .map(catalogPartition -> toSparkPartition(catalogPartition, catalogTable))
          .collect(Collectors.toList());
    } catch (NoSuchDatabaseException e) {
      throw SparkExceptionUtil.toUncheckedException(
          e, "Unknown table: %s. Database not found in catalog.", tableIdent);
    } catch (NoSuchTableException e) {
      throw SparkExceptionUtil.toUncheckedException(
          e, "Unknown table: %s. Table not found in catalog.", tableIdent);
    }
  }

  /**
   * Returns partitions that match the specified 'predicate'.
   *
   * @param spark a Spark session
   * @param table a table name and (optional) database
   * @param predicate a predicate on partition columns
   * @return matching table's partitions
   */
  public static List<SparkPartition> getPartitionsByFilter(
      SparkSession spark, String table, String predicate) {
    TableIdentifier tableIdent;
    try {
      tableIdent = spark.sessionState().sqlParser().parseTableIdentifier(table);
    } catch (ParseException e) {
      throw SparkExceptionUtil.toUncheckedException(
          e, "Unable to parse the table identifier: %s", table);
    }

    Expression unresolvedPredicateExpr;
    try {
      unresolvedPredicateExpr = spark.sessionState().sqlParser().parseExpression(predicate);
    } catch (ParseException e) {
      throw SparkExceptionUtil.toUncheckedException(
          e, "Unable to parse the predicate expression: %s", predicate);
    }

    Expression resolvedPredicateExpr = resolveAttrs(spark, table, unresolvedPredicateExpr);
    return getPartitionsByFilter(spark, tableIdent, resolvedPredicateExpr);
  }

  /**
   * Returns partitions that match the specified 'predicate'.
   *
   * @param spark a Spark session
   * @param tableIdent a table identifier
   * @param predicateExpr a predicate expression on partition columns
   * @return matching table's partitions
   */
  public static List<SparkPartition> getPartitionsByFilter(
      SparkSession spark, TableIdentifier tableIdent, Expression predicateExpr) {
    try {
      SessionCatalog catalog = spark.sessionState().catalog();
      CatalogTable catalogTable = catalog.getTableMetadata(tableIdent);

      Expression resolvedPredicateExpr;
      if (!predicateExpr.resolved()) {
        resolvedPredicateExpr = resolveAttrs(spark, tableIdent.quotedString(), predicateExpr);
      } else {
        resolvedPredicateExpr = predicateExpr;
      }
      Seq<Expression> predicates =
          JavaConverters.collectionAsScalaIterableConverter(ImmutableList.of(resolvedPredicateExpr))
              .asScala()
              .toIndexedSeq();

      Seq<CatalogTablePartition> partitions =
          catalog.listPartitionsByFilter(tableIdent, predicates).toIndexedSeq();

      return JavaConverters.seqAsJavaListConverter(partitions).asJava().stream()
          .map(catalogPartition -> toSparkPartition(catalogPartition, catalogTable))
          .collect(Collectors.toList());
    } catch (NoSuchDatabaseException e) {
      throw SparkExceptionUtil.toUncheckedException(
          e, "Unknown table: %s. Database not found in catalog.", tableIdent);
    } catch (NoSuchTableException e) {
      throw SparkExceptionUtil.toUncheckedException(
          e, "Unknown table: %s. Table not found in catalog.", tableIdent);
    }
  }

  private static List<DataFile> listPartition(
      SparkPartition partition,
      PartitionSpec spec,
      SerializableConfiguration conf,
      MetricsConfig metricsConfig,
      NameMapping mapping,
      boolean ignoreMissingFiles,
      ExecutorService service) {
    try {
      return TableMigrationUtil.listPartition(
          partition.values,
          partition.uri,
          partition.format,
          spec,
          conf.get(),
          metricsConfig,
          mapping,
          service);
    } catch (RuntimeException e) {
      if (ignoreMissingFiles && e.getCause() instanceof FileNotFoundException) {
        LOG.warn("Ignoring FileNotFoundException when listing partition of {}", partition, e);
        return Collections.emptyList();
      } else {
        throw e;
      }
    }
  }

  private static SparkPartition toSparkPartition(
      CatalogTablePartition partition, CatalogTable table) {
    Option<URI> locationUri = partition.storage().locationUri();
    Option<String> serde = partition.storage().serde();

    Preconditions.checkArgument(locationUri.nonEmpty(), "Partition URI should be defined");
    Preconditions.checkArgument(
        serde.nonEmpty() || table.provider().nonEmpty(), "Partition format should be defined");

    String uri = Util.uriToString(locationUri.get());
    String format = serde.nonEmpty() ? serde.get() : table.provider().get();

    Map<String, String> partitionSpec =
        JavaConverters.mapAsJavaMapConverter(partition.spec()).asJava();
    return new SparkPartition(partitionSpec, uri, format);
  }

  private static Expression resolveAttrs(SparkSession spark, String table, Expression expr) {
    Function2<String, String, Object> resolver = spark.sessionState().analyzer().resolver();
    LogicalPlan plan = spark.table(table).queryExecution().analyzed();
    return expr.transform(
        new AbstractPartialFunction<Expression, Expression>() {
          @Override
          public Expression apply(Expression attr) {
            UnresolvedAttribute unresolvedAttribute = (UnresolvedAttribute) attr;
            Option<NamedExpression> namedExpressionOption =
                plan.resolve(unresolvedAttribute.nameParts(), resolver);
            if (namedExpressionOption.isDefined()) {
              return (Expression) namedExpressionOption.get();
            } else {
              throw new IllegalArgumentException(
                  String.format("Could not resolve %s using columns: %s", attr, plan.output()));
            }
          }

          @Override
          public boolean isDefinedAt(Expression attr) {
            return attr instanceof UnresolvedAttribute;
          }
        });
  }

  private static Iterator<ManifestFile> buildManifest(
      SerializableConfiguration conf,
      PartitionSpec spec,
      String basePath,
      Iterator<Tuple2<String, DataFile>> fileTuples) {
    if (fileTuples.hasNext()) {
      FileIO io = new HadoopFileIO(conf.get());
      TaskContext ctx = TaskContext.get();
      String suffix =
          String.format(
              "stage-%d-task-%d-manifest-%s",
              ctx.stageId(), ctx.taskAttemptId(), UUID.randomUUID());
      Path location = new Path(basePath, suffix);
      String outputPath = FileFormat.AVRO.addExtension(location.toString());
      OutputFile outputFile = io.newOutputFile(outputPath);
      ManifestWriter<DataFile> writer = ManifestFiles.write(spec, outputFile);

      try (ManifestWriter<DataFile> writerRef = writer) {
        fileTuples.forEachRemaining(fileTuple -> writerRef.add(fileTuple._2));
      } catch (IOException e) {
        throw SparkExceptionUtil.toUncheckedException(
            e, "Unable to close the manifest writer: %s", outputPath);
      }

      ManifestFile manifestFile = writer.toManifestFile();
      return ImmutableList.of(manifestFile).iterator();
    } else {
      return Collections.emptyIterator();
    }
  }

  /**
   * Import files from an existing Spark table to an Iceberg table.
   *
   * <p>The import uses the Spark session to get table metadata. It assumes no operation is going on
   * the original and target table and thus is not thread-safe.
   *
   * @param spark a Spark session
   * @param sourceTableIdent an identifier of the source Spark table
   * @param targetTable an Iceberg table where to import the data
   * @param stagingDir a staging directory to store temporary manifest files
   * @param partitionFilter only import partitions whose values match those in the map, can be
   *     partially defined
   * @param checkDuplicateFiles if true, throw exception if import results in a duplicate data file
   */
  public static void importSparkTable(
      SparkSession spark,
      TableIdentifier sourceTableIdent,
      Table targetTable,
      String stagingDir,
      Map<String, String> partitionFilter,
      boolean checkDuplicateFiles) {
    importSparkTable(
        spark, sourceTableIdent, targetTable, stagingDir, partitionFilter, checkDuplicateFiles, 1);
  }

  /**
   * Import files from an existing Spark table to an Iceberg table.
   *
   * <p>The import uses the Spark session to get table metadata. It assumes no operation is going on
   * the original and target table and thus is not thread-safe.
   *
   * @param spark a Spark session
   * @param sourceTableIdent an identifier of the source Spark table
   * @param targetTable an Iceberg table where to import the data
   * @param stagingDir a staging directory to store temporary manifest files
   * @param parallelism number of threads to use for file reading
   */
  public static void importSparkTable(
      SparkSession spark,
      TableIdentifier sourceTableIdent,
      Table targetTable,
      String stagingDir,
      int parallelism) {
    importSparkTable(
        spark,
        sourceTableIdent,
        targetTable,
        stagingDir,
        TableMigrationUtil.migrationService(parallelism));
  }

  /**
   * Import files from an existing Spark table to an Iceberg table.
   *
   * <p>The import uses the Spark session to get table metadata. It assumes no operation is going on
   * the original and target table and thus is not thread-safe.
   *
   * @param spark a Spark session
   * @param sourceTableIdent an identifier of the source Spark table
   * @param targetTable an Iceberg table where to import the data
   * @param stagingDir a staging directory to store temporary manifest files
   * @param service executor service to use for file reading. If null, file reading will be
   *     performed on the current thread. If non-null, the provided ExecutorService will be shutdown
   *     within this method after file reading is complete.
   */
  public static void importSparkTable(
      SparkSession spark,
      TableIdentifier sourceTableIdent,
      Table targetTable,
      String stagingDir,
      ExecutorService service) {
    importSparkTable(
        spark, sourceTableIdent, targetTable, stagingDir, Collections.emptyMap(), false, service);
  }

  /**
   * Import files from an existing Spark table to an Iceberg table.
   *
   * <p>The import uses the Spark session to get table metadata. It assumes no operation is going on
   * the original and target table and thus is not thread-safe.
   *
   * @param spark a Spark session
   * @param sourceTableIdent an identifier of the source Spark table
   * @param targetTable an Iceberg table where to import the data
   * @param stagingDir a staging directory to store temporary manifest files
   * @param partitionFilter only import partitions whose values match those in the map, can be
   *     partially defined
   * @param checkDuplicateFiles if true, throw exception if import results in a duplicate data file
   * @param parallelism number of threads to use for file reading
   */
  public static void importSparkTable(
      SparkSession spark,
      TableIdentifier sourceTableIdent,
      Table targetTable,
      String stagingDir,
      Map<String, String> partitionFilter,
      boolean checkDuplicateFiles,
      int parallelism) {
    importSparkTable(
        spark,
        sourceTableIdent,
        targetTable,
        stagingDir,
        partitionFilter,
        checkDuplicateFiles,
        migrationService(parallelism));
  }

  /**
   * Import files from an existing Spark table to an Iceberg table.
   *
   * <p>The import uses the Spark session to get table metadata. It assumes no operation is going on
   * the original and target table and thus is not thread-safe.
   *
   * @param spark a Spark session
   * @param sourceTableIdent an identifier of the source Spark table
   * @param targetTable an Iceberg table where to import the data
   * @param stagingDir a staging directory to store temporary manifest files
   * @param partitionFilter only import partitions whose values match those in the map, can be
   *     partially defined
   * @param checkDuplicateFiles if true, throw exception if import results in a duplicate data file
   * @param service executor service to use for file reading. If null, file reading will be
   *     performed on the current thread. If non-null, the provided ExecutorService will be shutdown
   *     within this method after file reading is complete.
   */
  public static void importSparkTable(
      SparkSession spark,
      TableIdentifier sourceTableIdent,
      Table targetTable,
      String stagingDir,
      Map<String, String> partitionFilter,
      boolean checkDuplicateFiles,
      ExecutorService service) {
    importSparkTable(
        spark,
        sourceTableIdent,
        targetTable,
        stagingDir,
        partitionFilter,
        checkDuplicateFiles,
        false,
        service);
  }

  /**
   * Import files from an existing Spark table to an Iceberg table.
   *
   * <p>The import uses the Spark session to get table metadata. It assumes no operation is going on
   * the original and target table and thus is not thread-safe.
   *
   * @param spark a Spark session
   * @param sourceTableIdent an identifier of the source Spark table
   * @param targetTable an Iceberg table where to import the data
   * @param stagingDir a staging directory to store temporary manifest files
   * @param partitionFilter only import partitions whose values match those in the map, can be
   *     partially defined
   * @param checkDuplicateFiles if true, throw exception if import results in a duplicate data file
   * @param ignoreMissingFiles if true, ignore {@link FileNotFoundException} when running {@link
   *     #listPartition} for the Spark partitions
   * @param service executor service to use for file reading. If null, file reading will be
   *     performed on the current thread. If non-null, the provided ExecutorService will be shutdown
   *     within this method after file reading is complete.
   */
  public static void importSparkTable(
      SparkSession spark,
      TableIdentifier sourceTableIdent,
      Table targetTable,
      String stagingDir,
      Map<String, String> partitionFilter,
      boolean checkDuplicateFiles,
      boolean ignoreMissingFiles,
      ExecutorService service) {
    SessionCatalog catalog = spark.sessionState().catalog();

    String db =
        sourceTableIdent.database().nonEmpty()
            ? sourceTableIdent.database().get()
            : catalog.getCurrentDatabase();
    TableIdentifier sourceTableIdentWithDB =
        new TableIdentifier(sourceTableIdent.table(), Some.apply(db));

    if (!catalog.tableExists(sourceTableIdentWithDB)) {
      throw new org.apache.iceberg.exceptions.NoSuchTableException(
          "Table %s does not exist", sourceTableIdentWithDB);
    }

    try {
      PartitionSpec spec =
          findCompatibleSpec(targetTable, spark, sourceTableIdentWithDB.unquotedString());

      validatePartitionFilter(spec, partitionFilter, targetTable.name());

      if (Objects.equal(spec, PartitionSpec.unpartitioned())) {
        importUnpartitionedSparkTable(
            spark, sourceTableIdentWithDB, targetTable, checkDuplicateFiles, service);
      } else {
        List<SparkPartition> sourceTablePartitions =
            getPartitions(spark, sourceTableIdent, partitionFilter);
        if (sourceTablePartitions.isEmpty()) {
          targetTable.newAppend().commit();
        } else {
          importSparkPartitions(
              spark,
              sourceTablePartitions,
              targetTable,
              spec,
              stagingDir,
              checkDuplicateFiles,
              ignoreMissingFiles,
              service);
        }
      }
    } catch (AnalysisException e) {
      throw SparkExceptionUtil.toUncheckedException(
          e, "Unable to get partition spec for table: %s", sourceTableIdentWithDB);
    }
  }

  /**
   * Import files from an existing Spark table to an Iceberg table.
   *
   * <p>The import uses the Spark session to get table metadata. It assumes no operation is going on
   * the original and target table and thus is not thread-safe.
   *
   * @param spark a Spark session
   * @param sourceTableIdent an identifier of the source Spark table
   * @param targetTable an Iceberg table where to import the data
   * @param stagingDir a staging directory to store temporary manifest files
   * @param checkDuplicateFiles if true, throw exception if import results in a duplicate data file
   */
  public static void importSparkTable(
      SparkSession spark,
      TableIdentifier sourceTableIdent,
      Table targetTable,
      String stagingDir,
      boolean checkDuplicateFiles) {
    importSparkTable(
        spark,
        sourceTableIdent,
        targetTable,
        stagingDir,
        Collections.emptyMap(),
        checkDuplicateFiles,
        1);
  }

  /**
   * Import files from an existing Spark table to an Iceberg table.
   *
   * <p>The import uses the Spark session to get table metadata. It assumes no operation is going on
   * the original and target table and thus is not thread-safe.
   *
   * @param spark a Spark session
   * @param sourceTableIdent an identifier of the source Spark table
   * @param targetTable an Iceberg table where to import the data
   * @param stagingDir a staging directory to store temporary manifest files
   */
  public static void importSparkTable(
      SparkSession spark, TableIdentifier sourceTableIdent, Table targetTable, String stagingDir) {
    importSparkTable(
        spark, sourceTableIdent, targetTable, stagingDir, Collections.emptyMap(), false, 1);
  }

  private static void importUnpartitionedSparkTable(
      SparkSession spark,
      TableIdentifier sourceTableIdent,
      Table targetTable,
      boolean checkDuplicateFiles,
      ExecutorService service) {
    try {
      CatalogTable sourceTable = spark.sessionState().catalog().getTableMetadata(sourceTableIdent);
      Option<String> format =
          sourceTable.storage().serde().nonEmpty()
              ? sourceTable.storage().serde()
              : sourceTable.provider();
      Preconditions.checkArgument(format.nonEmpty(), "Could not determine table format");

      Map<String, String> partition = Collections.emptyMap();
      PartitionSpec spec = PartitionSpec.unpartitioned();
      Configuration conf = spark.sessionState().newHadoopConf();
      MetricsConfig metricsConfig = MetricsConfig.forTable(targetTable);
      String nameMappingString = targetTable.properties().get(TableProperties.DEFAULT_NAME_MAPPING);
      NameMapping nameMapping =
          nameMappingString != null ? NameMappingParser.fromJson(nameMappingString) : null;

      List<DataFile> files =
          TableMigrationUtil.listPartition(
              partition,
              Util.uriToString(sourceTable.location()),
              format.get(),
              spec,
              conf,
              metricsConfig,
              nameMapping,
              service);

      if (checkDuplicateFiles) {
        Dataset<Row> importedFiles =
            spark
                .createDataset(Lists.transform(files, ContentFile::location), Encoders.STRING())
                .toDF("file_path");
        Dataset<Row> existingFiles =
            loadMetadataTable(spark, targetTable, MetadataTableType.ENTRIES).filter("status != 2");
        Column joinCond =
            existingFiles.col("data_file.file_path").equalTo(importedFiles.col("file_path"));
        Dataset<String> duplicates =
            importedFiles.join(existingFiles, joinCond).select("file_path").as(Encoders.STRING());
        Preconditions.checkState(
            duplicates.isEmpty(),
            String.format(
                DUPLICATE_FILE_MESSAGE, Joiner.on(",").join((String[]) duplicates.take(10))));
      }

      AppendFiles append = targetTable.newAppend();
      files.forEach(append::appendFile);
      append.commit();
    } catch (NoSuchDatabaseException e) {
      throw SparkExceptionUtil.toUncheckedException(
          e, "Unknown table: %s. Database not found in catalog.", sourceTableIdent);
    } catch (NoSuchTableException e) {
      throw SparkExceptionUtil.toUncheckedException(
          e, "Unknown table: %s. Table not found in catalog.", sourceTableIdent);
    }
  }

  /**
   * Import files from given partitions to an Iceberg table.
   *
   * @param spark a Spark session
   * @param partitions partitions to import
   * @param targetTable an Iceberg table where to import the data
   * @param spec a partition spec
   * @param stagingDir a staging directory to store temporary manifest files
   * @param checkDuplicateFiles if true, throw exception if import results in a duplicate data file
   */
  public static void importSparkPartitions(
      SparkSession spark,
      List<SparkPartition> partitions,
      Table targetTable,
      PartitionSpec spec,
      String stagingDir,
      boolean checkDuplicateFiles) {
    importSparkPartitions(spark, partitions, targetTable, spec, stagingDir, checkDuplicateFiles, 1);
  }

  /**
   * Import files from given partitions to an Iceberg table.
   *
   * @param spark a Spark session
   * @param partitions partitions to import
   * @param targetTable an Iceberg table where to import the data
   * @param spec a partition spec
   * @param stagingDir a staging directory to store temporary manifest files
   * @param checkDuplicateFiles if true, throw exception if import results in a duplicate data file
   * @param parallelism number of threads to use for file reading
   */
  public static void importSparkPartitions(
      SparkSession spark,
      List<SparkPartition> partitions,
      Table targetTable,
      PartitionSpec spec,
      String stagingDir,
      boolean checkDuplicateFiles,
      int parallelism) {
    importSparkPartitions(
        spark,
        partitions,
        targetTable,
        spec,
        stagingDir,
        checkDuplicateFiles,
        migrationService(parallelism));
  }

  /**
   * Import files from given partitions to an Iceberg table.
   *
   * @param spark a Spark session
   * @param partitions partitions to import
   * @param targetTable an Iceberg table where to import the data
   * @param spec a partition spec
   * @param stagingDir a staging directory to store temporary manifest files
   * @param checkDuplicateFiles if true, throw exception if import results in a duplicate data file
   * @param service executor service to use for file reading. If null, file reading will be
   *     performed on the current thread. If non-null, the provided ExecutorService will be shutdown
   *     within this method after file reading is complete.
   */
  public static void importSparkPartitions(
      SparkSession spark,
      List<SparkPartition> partitions,
      Table targetTable,
      PartitionSpec spec,
      String stagingDir,
      boolean checkDuplicateFiles,
      ExecutorService service) {
    importSparkPartitions(
        spark, partitions, targetTable, spec, stagingDir, checkDuplicateFiles, false, service);
  }

  /**
   * Import files from given partitions to an Iceberg table.
   *
   * @param spark a Spark session
   * @param partitions partitions to import
   * @param targetTable an Iceberg table where to import the data
   * @param spec a partition spec
   * @param stagingDir a staging directory to store temporary manifest files
   * @param checkDuplicateFiles if true, throw exception if import results in a duplicate data file
   * @param ignoreMissingFiles if true, ignore {@link FileNotFoundException} when running {@link
   *     #listPartition} for the Spark partitions
   * @param service executor service to use for file reading. If null, file reading will be
   *     performed on the current thread. If non-null, the provided ExecutorService will be shutdown
   *     within this method after file reading is complete.
   */
  public static void importSparkPartitions(
      SparkSession spark,
      List<SparkPartition> partitions,
      Table targetTable,
      PartitionSpec spec,
      String stagingDir,
      boolean checkDuplicateFiles,
      boolean ignoreMissingFiles,
      ExecutorService service) {
    Configuration conf = spark.sessionState().newHadoopConf();
    SerializableConfiguration serializableConf = new SerializableConfiguration(conf);
    int listingParallelism =
        Math.min(
            partitions.size(), spark.sessionState().conf().parallelPartitionDiscoveryParallelism());
    int numShufflePartitions = spark.sessionState().conf().numShufflePartitions();
    MetricsConfig metricsConfig = MetricsConfig.fromProperties(targetTable.properties());
    String nameMappingString = targetTable.properties().get(TableProperties.DEFAULT_NAME_MAPPING);
    NameMapping nameMapping =
        nameMappingString != null ? NameMappingParser.fromJson(nameMappingString) : null;

    JavaSparkContext sparkContext = JavaSparkContext.fromSparkContext(spark.sparkContext());
    JavaRDD<SparkPartition> partitionRDD = sparkContext.parallelize(partitions, listingParallelism);

    Dataset<SparkPartition> partitionDS =
        spark.createDataset(partitionRDD.rdd(), Encoders.javaSerialization(SparkPartition.class));

    Dataset<DataFile> filesToImport =
        partitionDS.flatMap(
            (FlatMapFunction<SparkPartition, DataFile>)
                sparkPartition ->
                    listPartition(
                            sparkPartition,
                            spec,
                            serializableConf,
                            metricsConfig,
                            nameMapping,
                            ignoreMissingFiles,
                            service)
                        .iterator(),
            Encoders.javaSerialization(DataFile.class));

    if (checkDuplicateFiles) {
      Dataset<Row> importedFiles =
          filesToImport
              .map((MapFunction<DataFile, String>) ContentFile::location, Encoders.STRING())
              .toDF("file_path");
      Dataset<Row> existingFiles =
          loadMetadataTable(spark, targetTable, MetadataTableType.ENTRIES).filter("status != 2");
      Column joinCond =
          existingFiles.col("data_file.file_path").equalTo(importedFiles.col("file_path"));
      Dataset<String> duplicates =
          importedFiles.join(existingFiles, joinCond).select("file_path").as(Encoders.STRING());
      Preconditions.checkState(
          duplicates.isEmpty(),
          String.format(
              DUPLICATE_FILE_MESSAGE, Joiner.on(",").join((String[]) duplicates.take(10))));
    }

    List<ManifestFile> manifests =
        filesToImport
            .repartition(numShufflePartitions)
            .map(
                (MapFunction<DataFile, Tuple2<String, DataFile>>)
                    file -> Tuple2.apply(file.location(), file),
                Encoders.tuple(Encoders.STRING(), Encoders.javaSerialization(DataFile.class)))
            .orderBy(col("_1"))
            .mapPartitions(
                (MapPartitionsFunction<Tuple2<String, DataFile>, ManifestFile>)
                    fileTuple -> buildManifest(serializableConf, spec, stagingDir, fileTuple),
                Encoders.javaSerialization(ManifestFile.class))
            .collectAsList();

    try {
      TableOperations ops = ((HasTableOperations) targetTable).operations();
      int formatVersion = ops.current().formatVersion();
      boolean snapshotIdInheritanceEnabled =
          PropertyUtil.propertyAsBoolean(
              targetTable.properties(),
              TableProperties.SNAPSHOT_ID_INHERITANCE_ENABLED,
              TableProperties.SNAPSHOT_ID_INHERITANCE_ENABLED_DEFAULT);

      AppendFiles append = targetTable.newAppend();
      manifests.forEach(append::appendManifest);
      append.commit();

      if (formatVersion == 1 && !snapshotIdInheritanceEnabled) {
        // delete original manifests as they were rewritten before the commit
        deleteManifests(targetTable.io(), manifests);
      }
    } catch (Throwable e) {
      deleteManifests(targetTable.io(), manifests);
      throw e;
    }
  }

  /**
   * Import files from given partitions to an Iceberg table.
   *
   * @param spark a Spark session
   * @param partitions partitions to import
   * @param targetTable an Iceberg table where to import the data
   * @param spec a partition spec
   * @param stagingDir a staging directory to store temporary manifest files
   */
  public static void importSparkPartitions(
      SparkSession spark,
      List<SparkPartition> partitions,
      Table targetTable,
      PartitionSpec spec,
      String stagingDir) {
    importSparkPartitions(spark, partitions, targetTable, spec, stagingDir, false, 1);
  }

  public static List<SparkPartition> filterPartitions(
      List<SparkPartition> partitions, Map<String, String> partitionFilter) {
    if (partitionFilter.isEmpty()) {
      return partitions;
    } else {
      return partitions.stream()
          .filter(p -> p.getValues().entrySet().containsAll(partitionFilter.entrySet()))
          .collect(Collectors.toList());
    }
  }

  private static void deleteManifests(FileIO io, List<ManifestFile> manifests) {
    if (io instanceof SupportsBulkOperations) {
      ((SupportsBulkOperations) io).deleteFiles(Lists.transform(manifests, ManifestFile::path));
    } else {
      Tasks.foreach(manifests)
          .executeWith(ThreadPools.getWorkerPool())
          .noRetry()
          .suppressFailureWhenFinished()
          .run(item -> io.deleteFile(item.path()));
    }
  }

  public static Dataset<Row> loadTable(SparkSession spark, Table table, long snapshotId) {
    SparkTable sparkTable = new SparkTable(table, snapshotId, false);
    DataSourceV2Relation relation = createRelation(sparkTable, ImmutableMap.of());
    return Dataset.ofRows(spark, relation);
  }

  public static Dataset<Row> loadMetadataTable(
      SparkSession spark, Table table, MetadataTableType type) {
    return loadMetadataTable(spark, table, type, ImmutableMap.of());
  }

  public static Dataset<Row> loadMetadataTable(
      SparkSession spark, Table table, MetadataTableType type, Map<String, String> extraOptions) {
    Table metadataTable = MetadataTableUtils.createMetadataTableInstance(table, type);
    SparkTable sparkMetadataTable = new SparkTable(metadataTable, false);
    DataSourceV2Relation relation = createRelation(sparkMetadataTable, extraOptions);
    return Dataset.ofRows(spark, relation);
  }

  private static DataSourceV2Relation createRelation(
      SparkTable sparkTable, Map<String, String> extraOptions) {
    CaseInsensitiveStringMap options = new CaseInsensitiveStringMap(extraOptions);
    return DataSourceV2Relation.create(sparkTable, Option.empty(), Option.empty(), options);
  }

  /**
   * Determine the write branch.
   *
   * <p>Validate wap config and determine the write branch.
   *
   * @param spark a Spark Session
   * @param branch write branch if there is no WAP branch configured
   * @return branch for write operation
   */
  public static String determineWriteBranch(SparkSession spark, String branch) {
    String wapId = spark.conf().get(SparkSQLProperties.WAP_ID, null);
    String wapBranch = spark.conf().get(SparkSQLProperties.WAP_BRANCH, null);
    ValidationException.check(
        wapId == null || wapBranch == null,
        "Cannot set both WAP ID and branch, but got ID [%s] and branch [%s]",
        wapId,
        wapBranch);

    if (wapBranch != null) {
      ValidationException.check(
          branch == null,
          "Cannot write to both branch and WAP branch, but got branch [%s] and WAP branch [%s]",
          branch,
          wapBranch);

      return wapBranch;
    }
    return branch;
  }

  public static boolean wapEnabled(Table table) {
    return PropertyUtil.propertyAsBoolean(
        table.properties(),
        TableProperties.WRITE_AUDIT_PUBLISH_ENABLED,
        Boolean.parseBoolean(TableProperties.WRITE_AUDIT_PUBLISH_ENABLED_DEFAULT));
  }

  /** Class representing a table partition. */
  public static class SparkPartition implements Serializable {
    private final Map<String, String> values;
    private final String uri;
    private final String format;

    public SparkPartition(Map<String, String> values, String uri, String format) {
      this.values = Maps.newHashMap(values);
      this.uri = uri;
      this.format = format;
    }

    public Map<String, String> getValues() {
      return values;
    }

    public String getUri() {
      return uri;
    }

    public String getFormat() {
      return format;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("values", values)
          .add("uri", uri)
          .add("format", format)
          .toString();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      SparkPartition that = (SparkPartition) o;
      return Objects.equal(values, that.values)
          && Objects.equal(uri, that.uri)
          && Objects.equal(format, that.format);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(values, uri, format);
    }
  }

  /**
   * Returns the first partition spec in an IcebergTable that shares the same names and ordering as
   * the partition columns provided. Throws an error if not found
   */
  public static PartitionSpec findCompatibleSpec(List<String> partitionNames, Table icebergTable) {
    List<String> partitionNamesLower =
        partitionNames.stream()
            .map(name -> name.toLowerCase(Locale.ROOT))
            .collect(Collectors.toList());
    for (PartitionSpec icebergSpec : icebergTable.specs().values()) {
      boolean allIdentity =
          icebergSpec.fields().stream().allMatch(field -> field.transform().isIdentity());
      if (allIdentity) {
        List<String> icebergPartNames =
            icebergSpec.fields().stream()
                .map(PartitionField::name)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toList());
        if (icebergPartNames.equals(partitionNamesLower)) {
          return icebergSpec;
        }
      }
    }

    throw new IllegalArgumentException(
        String.format(
            "Cannot find a partition spec in Iceberg table %s that matches the partition"
                + " columns (%s) in input table",
            icebergTable, partitionNames));
  }

  /**
   * Returns the first partition spec in an IcebergTable that shares the same names and ordering as
   * the partition columns provided. Throws an error if not found
   */
  private static PartitionSpec findCompatibleSpec(
      Table icebergTable, SparkSession spark, String sparkTable) throws AnalysisException {
    List<String> parts = Lists.newArrayList(Splitter.on('.').limit(2).split(sparkTable));
    String db = parts.size() == 1 ? "default" : parts.get(0);
    String table = parts.get(parts.size() == 1 ? 0 : 1);

    List<String> sparkPartNames =
        spark.catalog().listColumns(db, table).collectAsList().stream()
            .filter(org.apache.spark.sql.catalog.Column::isPartition)
            .map(org.apache.spark.sql.catalog.Column::name)
            .collect(Collectors.toList());
    return findCompatibleSpec(sparkPartNames, icebergTable);
  }

  public static void validatePartitionFilter(
      PartitionSpec spec, Map<String, String> partitionFilter, String tableName) {
    List<PartitionField> partitionFields = spec.fields();
    Set<String> partitionNames =
        spec.fields().stream().map(PartitionField::name).collect(Collectors.toSet());

    boolean tablePartitioned = !partitionFields.isEmpty();
    boolean partitionFilterPassed = !partitionFilter.isEmpty();

    if (tablePartitioned && partitionFilterPassed) {
      // Check to see there are sufficient partition columns to satisfy the filter
      Preconditions.checkArgument(
          partitionFields.size() >= partitionFilter.size(),
          "Cannot add data files to target table %s because that table is partitioned, "
              + "but the number of columns in the provided partition filter (%s) "
              + "is greater than the number of partitioned columns in table (%s)",
          tableName,
          partitionFilter.size(),
          partitionFields.size());

      // Check for any filters of non-existent columns
      List<String> unMatchedFilters =
          partitionFilter.keySet().stream()
              .filter(filterName -> !partitionNames.contains(filterName))
              .collect(Collectors.toList());
      Preconditions.checkArgument(
          unMatchedFilters.isEmpty(),
          "Cannot add files to target table %s. %s is partitioned but the specified partition filter "
              + "refers to columns that are not partitioned: %s . Valid partition columns: [%s]",
          tableName,
          tableName,
          unMatchedFilters,
          String.join(",", partitionNames));
    } else {
      Preconditions.checkArgument(
          !partitionFilterPassed,
          "Cannot use partition filter with an unpartitioned table %s",
          tableName);
    }
  }

  @Nullable
  public static ExecutorService migrationService(int parallelism) {
    return parallelism == 1 ? null : new LazyExecutorService(parallelism);
  }

  private static class LazyExecutorService implements ExecutorService, Serializable {

    private final int parallelism;
    private volatile ExecutorService service;

    LazyExecutorService(int parallelism) {
      this.parallelism = parallelism;
    }

    @Override
    public void shutdown() {
      getService().shutdown();
    }

    @NotNull
    @Override
    public List<Runnable> shutdownNow() {
      return getService().shutdownNow();
    }

    @Override
    public boolean isShutdown() {
      return getService().isShutdown();
    }

    @Override
    public boolean isTerminated() {
      return getService().isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, @NotNull TimeUnit unit)
        throws InterruptedException {
      return getService().awaitTermination(timeout, unit);
    }

    @NotNull
    @Override
    public <T> Future<T> submit(@NotNull Callable<T> task) {
      return getService().submit(task);
    }

    @NotNull
    @Override
    public <T> Future<T> submit(@NotNull Runnable task, T result) {
      return getService().submit(task, result);
    }

    @NotNull
    @Override
    public Future<?> submit(@NotNull Runnable task) {
      return getService().submit(task);
    }

    @NotNull
    @Override
    public <T> List<Future<T>> invokeAll(@NotNull Collection<? extends Callable<T>> tasks)
        throws InterruptedException {
      return getService().invokeAll(tasks);
    }

    @NotNull
    @Override
    public <T> List<Future<T>> invokeAll(
        @NotNull Collection<? extends Callable<T>> tasks, long timeout, @NotNull TimeUnit unit)
        throws InterruptedException {
      return getService().invokeAll(tasks, timeout, unit);
    }

    @NotNull
    @Override
    public <T> T invokeAny(@NotNull Collection<? extends Callable<T>> tasks)
        throws InterruptedException, ExecutionException {
      return getService().invokeAny(tasks);
    }

    @Override
    public <T> T invokeAny(
        @NotNull Collection<? extends Callable<T>> tasks, long timeout, @NotNull TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException {
      return getService().invokeAny(tasks, timeout, unit);
    }

    @Override
    public void execute(@NotNull Runnable command) {
      getService().execute(command);
    }

    private ExecutorService getService() {
      if (service == null) {
        synchronized (this) {
          if (service == null) {
            service = TableMigrationUtil.migrationService(parallelism);
          }
        }
      }
      return service;
    }
  }
}
