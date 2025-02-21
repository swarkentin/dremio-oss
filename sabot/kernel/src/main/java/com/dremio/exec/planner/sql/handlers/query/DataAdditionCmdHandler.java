/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.exec.planner.sql.handlers.query;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.util.NlsString;
import org.apache.calcite.util.Pair;
import org.apache.iceberg.PartitionField;
import org.apache.iceberg.Table;

import com.dremio.common.exceptions.UserException;
import com.dremio.common.utils.protos.QueryIdHelper;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.catalog.CatalogOptions;
import com.dremio.exec.catalog.ColumnCountTooLargeException;
import com.dremio.exec.catalog.DatasetCatalog;
import com.dremio.exec.catalog.DatasetCatalog.UpdateStatus;
import com.dremio.exec.catalog.DremioTable;
import com.dremio.exec.catalog.MutablePlugin;
import com.dremio.exec.catalog.ResolvedVersionContext;
import com.dremio.exec.catalog.SourceCatalog;
import com.dremio.exec.catalog.VersionedPlugin;
import com.dremio.exec.physical.PhysicalPlan;
import com.dremio.exec.physical.base.PhysicalOperator;
import com.dremio.exec.physical.base.WriterOptions;
import com.dremio.exec.planner.DremioVolcanoPlanner;
import com.dremio.exec.planner.logical.CreateTableEntry;
import com.dremio.exec.planner.logical.Rel;
import com.dremio.exec.planner.logical.ScreenRel;
import com.dremio.exec.planner.logical.WriterRel;
import com.dremio.exec.planner.physical.PlannerSettings;
import com.dremio.exec.planner.physical.Prel;
import com.dremio.exec.planner.physical.PrelUtil;
import com.dremio.exec.planner.sql.CalciteArrowHelper;
import com.dremio.exec.planner.sql.SqlExceptionHelper;
import com.dremio.exec.planner.sql.handlers.ConvertedRelNode;
import com.dremio.exec.planner.sql.handlers.PrelTransformer;
import com.dremio.exec.planner.sql.handlers.SqlHandlerConfig;
import com.dremio.exec.planner.sql.handlers.SqlHandlerUtil;
import com.dremio.exec.planner.sql.handlers.ViewAccessEvaluator;
import com.dremio.exec.planner.sql.parser.DataAdditionCmdCall;
import com.dremio.exec.planner.sql.parser.SqlCreateEmptyTable;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.DatasetRetrievalOptions;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.exec.store.dfs.FileSystemCreateTableEntry;
import com.dremio.exec.store.dfs.FileSystemPlugin;
import com.dremio.exec.store.dfs.IcebergTableProps;
import com.dremio.exec.store.iceberg.SchemaConverter;
import com.dremio.exec.store.iceberg.model.IcebergCommandType;
import com.dremio.exec.store.iceberg.model.IcebergModel;
import com.dremio.exec.work.foreman.SqlUnsupportedException;
import com.dremio.options.OptionManager;
import com.dremio.service.namespace.NamespaceKey;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

import io.protostuff.ByteString;

public abstract class DataAdditionCmdHandler implements SqlToPlanHandler {
  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(DataAdditionCmdHandler.class);


  private String textPlan;
  private FileSystemCreateTableEntry icebergCreateTableEntry = null;
  private BatchSchema tableSchemaFromKVStore = null;
  private List<String> partitionColumns = null;
  private Map<String, Object> storageOptionsMap = null;
  private boolean isIcebergTable = false;
  private DremioTable cachedDremioTable = null;
  private boolean dremioTableFetched = false;
  private boolean isVersionedTable = false;

  public DataAdditionCmdHandler() {
  }

  public boolean isIcebergTable() {
    return isIcebergTable;
  }
  private boolean isVersionedTable() {
    return isVersionedTable;
  }

  public WriterOptions.IcebergWriterOperation getIcebergWriterOperation() {
    if (!isIcebergTable) {
      return WriterOptions.IcebergWriterOperation.NONE;
    }
    return isCreate() ? WriterOptions.IcebergWriterOperation.CREATE : WriterOptions.IcebergWriterOperation.INSERT;
  }

  public abstract boolean isCreate();

  public PhysicalPlan getPlan(DatasetCatalog datasetCatalog,
                              NamespaceKey path,
                              SqlHandlerConfig config,
                              String sql,
                              DataAdditionCmdCall sqlCmd,
                              ResolvedVersionContext version
  ) throws Exception {
    try {
      final ConvertedRelNode convertedRelNode = PrelTransformer.validateAndConvert(config, sqlCmd.getQuery());
      final RelDataType validatedRowType = convertedRelNode.getValidatedRowType();

      long maxColumnCount = config.getContext().getOptions().getOption(CatalogOptions.METADATA_LEAF_COLUMN_MAX);
      if (validatedRowType.getFieldCount() > maxColumnCount) {
        throw new ColumnCountTooLargeException((int) maxColumnCount);
      }

      // Get and cache table info (if not already retrieved) to avoid multiple retrievals
      getDremioTable(datasetCatalog, path);
      final RelNode queryRelNode = convertedRelNode.getConvertedNode();
      ViewAccessEvaluator viewAccessEvaluator = null;
      if (config.getConverter().getSubstitutionProvider().isDefaultRawReflectionEnabled()) {
        final RelNode convertedRelWithExpansionNodes = ((DremioVolcanoPlanner) queryRelNode.getCluster().getPlanner()).getOriginalRoot();
        viewAccessEvaluator = new ViewAccessEvaluator(convertedRelWithExpansionNodes, config);
        config.getContext().getExecutorService().submit(viewAccessEvaluator);
      }
      final RelNode newTblRelNode = SqlHandlerUtil.resolveNewTableRel(false, sqlCmd.getFieldNames(),
          validatedRowType, queryRelNode, !isCreate());

      final long ringCount = config.getContext().getOptions().getOption(PlannerSettings.RING_COUNT);

      ByteString extendedByteString = null;
      if (!isCreate()) {
        extendedByteString = cachedDremioTable.getDatasetConfig().getReadDefinition().getExtendedProperty();
      }

      // For Insert command we make sure query schema match exactly with table schema,
      // which includes partition columns. So, not checking here
      final RelNode newTblRelNodeWithPCol = SqlHandlerUtil.qualifyPartitionCol(newTblRelNode,
        isCreate() ?
          sqlCmd.getPartitionColumns(null /*param is unused in this interface for create */) :
          Lists.newArrayList());

      PrelTransformer.log("Calcite", newTblRelNodeWithPCol, logger, null);

      final WriterOptions options = new WriterOptions(
        (int) ringCount,
        sqlCmd.getPartitionColumns(cachedDremioTable),
        sqlCmd.getSortColumns(),
        sqlCmd.getDistributionColumns(),
        sqlCmd.getPartitionDistributionStrategy(),
        sqlCmd.isSingleWriter(),
        Long.MAX_VALUE,
        getIcebergWriterOperation(),
        extendedByteString,
        version
      );

      // Convert the query to Dremio Logical plan and insert a writer operator on top.
      Rel drel = this.convertToDrel(
        config,
        newTblRelNodeWithPCol,
        datasetCatalog,
        path,
        options,
        newTblRelNode.getRowType(),
        storageOptionsMap, sqlCmd.getFieldNames());

      final Pair<Prel, String> convertToPrel = PrelTransformer.convertToPrel(config, drel);
      final Prel prel = convertToPrel.getKey();
      textPlan = convertToPrel.getValue();
      PhysicalOperator pop = PrelTransformer.convertToPop(config, prel);

      PhysicalPlan plan = PrelTransformer.convertToPlan(config, pop,
        isIcebergTable() && !isVersionedTable() ?
          () -> refreshDataset(datasetCatalog, path, isCreate())
          : null);

      PrelTransformer.log(config, "Dremio Plan", plan, logger);

      if (viewAccessEvaluator != null) {
        viewAccessEvaluator.getLatch().await(config.getContext().getPlannerSettings().getMaxPlanningPerPhaseMS(), TimeUnit.MILLISECONDS);
        if (viewAccessEvaluator.getException() != null) {
          throw viewAccessEvaluator.getException();
        }
      }

      return plan;

    } catch(Exception ex){
      throw SqlExceptionHelper.coerceException(logger, sql, ex, true);
    }
  }

  // Cache the table metadata so we can retrieve schema and partition columns and avoid double lookups
  protected DremioTable getDremioTable(DatasetCatalog datasetCatalog, NamespaceKey path) {
    if (dremioTableFetched == false) {
      cachedDremioTable = datasetCatalog.getTable(path);
      dremioTableFetched = true;
    }
    return cachedDremioTable;
  }

  protected void checkExistenceValidity(NamespaceKey path, DremioTable dremioTable) {
    if (dremioTable != null && isCreate()) {
      throw UserException.validationError()
        .message("A table or view with given name [%s] already exists.", path.toString())
        .build(logger);
    }
    if (dremioTable == null && !isCreate()) {
      throw UserException.validationError()
        .message("Table [%s] not found", path)
        .buildSilently();
    }
  }

  // For iceberg tables, do a refresh after the attempt completion.
  public static void refreshDataset(DatasetCatalog datasetCatalog, NamespaceKey key, boolean autopromote) {
    DatasetRetrievalOptions options = DatasetRetrievalOptions.DEFAULT;
    if (autopromote && !options.autoPromote()) {
      options = DatasetRetrievalOptions.newBuilder()
        .setAutoPromote(true)
        .build()
        .withFallback(DatasetRetrievalOptions.DEFAULT);
    }

    UpdateStatus updateStatus = datasetCatalog.refreshDataset(key, options);
    logger.info("refreshed{} dataset {}, update status \"{}\"",
      autopromote ? " and autopromoted" : "", key, updateStatus.name());
  }

  private Rel convertToDrel(
    SqlHandlerConfig config,
    RelNode relNode,
    DatasetCatalog datasetCatalog,
    NamespaceKey key,
    WriterOptions options,
    RelDataType queryRowType,
    final Map<String, Object> storageOptions,
    final List<String> fieldNames)
      throws SqlUnsupportedException {
    Rel convertedRelNode = PrelTransformer.convertToDrel(config, relNode);

    // Put a non-trivial topProject to ensure the final output field name is preserved, when necessary.
    // Only insert project when the field count from the child is same as that of the queryRowType.

    String queryId = "";
    IcebergTableProps icebergTableProps = null;
    if (isIcebergTable()) {
      queryId = QueryIdHelper.getQueryId(config.getContext().getQueryId());
      if (!isCreate()) {
        tableSchemaFromKVStore = cachedDremioTable.getSchema();
        partitionColumns = cachedDremioTable.getDatasetConfig().getReadDefinition().getPartitionColumnsList();
      }
      icebergTableProps = new IcebergTableProps(null, queryId,
        null,
        options.getPartitionColumns() ,
        isCreate() ? IcebergCommandType.CREATE : IcebergCommandType.INSERT,
        key.getName(), null, options.getVersion()); // TODO: DX-43311 Should we allow null version?
    }

    CreateTableEntry tableEntry = datasetCatalog.createNewTable(
      key,
      icebergTableProps,
      options,
      storageOptions);
    if (isIcebergTable()) {
      // Store file system create table entry, so that later we can perform supported schema and plugin validations
      Preconditions.checkState(tableEntry instanceof FileSystemCreateTableEntry, "Unexpected create table entry");
      Preconditions.checkState(icebergCreateTableEntry == null, "Unexpected state");
      icebergCreateTableEntry = (FileSystemCreateTableEntry)tableEntry;
      Preconditions.checkState(icebergCreateTableEntry.getIcebergTableProps().getTableLocation() != null &&
            !icebergCreateTableEntry.getIcebergTableProps().getTableLocation().isEmpty(),
        "Table folder location must not be empty");
    }

    if (!isCreate()) {
      BatchSchema partSchemaWithSelectedFields = tableSchemaFromKVStore.subset(fieldNames).orElse(tableSchemaFromKVStore);
      queryRowType = CalciteArrowHelper.wrap(partSchemaWithSelectedFields)
          .toCalciteRecordType(convertedRelNode.getCluster().getTypeFactory(), PrelUtil.getPlannerSettings(convertedRelNode.getCluster()).isFullNestedSchemaSupport());
    }

    convertedRelNode = new WriterRel(convertedRelNode.getCluster(),
      convertedRelNode.getCluster().traitSet().plus(Rel.LOGICAL),
      convertedRelNode, tableEntry, queryRowType);

    convertedRelNode = SqlHandlerUtil.storeQueryResultsIfNeeded(config.getConverter().getParserConfig(),
      config.getContext(), convertedRelNode);

    return new ScreenRel(convertedRelNode.getCluster(), convertedRelNode.getTraitSet(), convertedRelNode);
  }

  public void validateIcebergSchemaForInsertCommand(List<String> fieldNames) {
    IcebergTableProps icebergTableProps = icebergCreateTableEntry.getIcebergTableProps();
    Preconditions.checkState(icebergTableProps.getIcebergOpType() == IcebergCommandType.INSERT,
      "unexpected state found");

    BatchSchema querySchema = icebergTableProps.getFullSchema();
    IcebergModel icebergModel = icebergCreateTableEntry.getPlugin().getIcebergModel();
    Table table = icebergModel.getIcebergTable(icebergModel.getTableIdentifier(icebergTableProps.getTableLocation()));
    SchemaConverter schemaConverter = new SchemaConverter(table.name());
    BatchSchema icebergSchema = schemaConverter.fromIceberg(table.schema());

    // this check can be removed once we support schema evolution in dremio.
    if (!icebergSchema.equalsIgnoreCase(tableSchemaFromKVStore)) {
      throw UserException.validationError().message("The schema for table %s does not match with the iceberg %s.",
        tableSchemaFromKVStore, icebergSchema).buildSilently();
    }

    List<String> icebergPartitionColumns = table.spec().fields().stream()
      .map(PartitionField::name).collect(Collectors.toList());

    // this check can be removed once we support partition spec evolution in dremio.
    if (!comparePartitionColumnLists(icebergPartitionColumns)) {
      throw UserException.validationError().message("The table partition columns %s do not match with the iceberg partition columns %s.",
        partitionColumns.toString(), icebergPartitionColumns.toString()).buildSilently();
    }

    BatchSchema partSchemaWithSelectedFields = tableSchemaFromKVStore.subset(fieldNames).orElse(tableSchemaFromKVStore);
    if (!querySchema.equalsIgnoreCase(partSchemaWithSelectedFields)) {
      throw UserException.validationError().message("Table %s doesn't match with query %s.",
          partSchemaWithSelectedFields, querySchema).buildSilently();
    }
  }

  private boolean comparePartitionColumnLists(List<String> icebergPartitionColumns) {
    boolean icebergHasPartitionSpec = (icebergPartitionColumns != null && icebergPartitionColumns.size() > 0);
    boolean kvStoreHasPartitionSpec = (partitionColumns != null && partitionColumns.size() > 0);
    if (icebergHasPartitionSpec != kvStoreHasPartitionSpec) {
      return false;
    }

    if(!icebergHasPartitionSpec && !kvStoreHasPartitionSpec) {
      return true;
    }

    if (icebergPartitionColumns.size() != partitionColumns.size()) {
      return false;
    }

    for (int index = 0; index < icebergPartitionColumns.size(); ++index) {
      if (!icebergPartitionColumns.get(index).equalsIgnoreCase(partitionColumns.get(index))) {
        return false;
      }
    }

    return true;
  }

  /**
   * Helper method to create map of key, value pairs, the value is a Java type object.
   * @param args
   * @return
   */
  @VisibleForTesting
  public void createStorageOptionsMap(final SqlNodeList args) {
    if (args == null || args.size() == 0) {
      return;
    }

    final ImmutableMap.Builder<String, Object> storageOptions = ImmutableMap.builder();
    for (SqlNode operand : args) {
      if (operand.getKind() != SqlKind.ARGUMENT_ASSIGNMENT) {
        throw UserException.unsupportedError()
          .message("Unsupported argument type. Only assignment arguments (param => value) are supported.")
          .build(logger);
      }
      final List<SqlNode> operandList = ((SqlCall) operand).getOperandList();

      final String name = ((SqlIdentifier) operandList.get(1)).getSimple();
      SqlNode literal = operandList.get(0);
      if (!(literal instanceof SqlLiteral)) {
        throw UserException.unsupportedError()
          .message("Only literals are accepted for storage option values")
          .build(logger);
      }

      Object value = ((SqlLiteral)literal).getValue();
      if (value instanceof NlsString) {
        value = ((NlsString)value).getValue();
      }
      storageOptions.put(name, value);
    }

    this.storageOptionsMap = storageOptions.build();
  }

  public static boolean isIcebergFeatureEnabled(OptionManager options,
                                                Map<String, Object> storageOptionsMap) {
    if (!options.getOption(ExecConstants.ENABLE_ICEBERG)) {
      return false;
    }

    // Iceberg table format will not be used as a storage type explicitly
    // parquet/arrow/json formats specify in the options
    if (storageOptionsMap != null) {
      return false;
    }

    return true;
  }


  public static boolean isStorageOptionIceberg(OptionManager options,
                                               Map<String, Object> storageOptionsMap) {
    if (!options.getOption(ExecConstants.ENABLE_ICEBERG)) {
      return false;
    }

    if (storageOptionsMap != null) {
      return storageOptionsMap.get("type").equals("iceberg") && storageOptionsMap.size() == 1;
    }

    return false;
  }

  public static boolean isIcebergDMLFeatureEnabled(SourceCatalog sourceCatalog,
                                                   NamespaceKey path, OptionManager options,
                                                Map<String, Object> storageOptionsMap) {
    if (!isIcebergFeatureEnabled(options, storageOptionsMap)) {
      return false;
    }

    StoragePlugin storagePlugin = null;
    try {
      storagePlugin = sourceCatalog.getSource(path.getRoot());
    } catch (UserException uex) {
    }

    // to avoid existing test failures do not check for additional flags for Versioned and FileSystem plugins
    if (storagePlugin == null || storagePlugin instanceof VersionedPlugin || storagePlugin instanceof FileSystemPlugin) {
      return true;
    }

    if (!options.getOption(ExecConstants.ENABLE_ICEBERG_DML)) {
      return false;
    }
    return true;
  }

  public static boolean validatePath(SourceCatalog sourceCatalog,
                                     NamespaceKey path) {
    try {
      sourceCatalog.getSource(path.getRoot());
    } catch (UserException uex) {
      return false;
    }
    return true;
  }

  public static boolean validatePluginSupportForIceberg(SourceCatalog sourceCatalog,
                                                        NamespaceKey path) {
    StoragePlugin storagePlugin;
    try {
      storagePlugin = sourceCatalog.getSource(path.getRoot());
    } catch (UserException uex) {
      return false;
    }

    if (storagePlugin instanceof VersionedPlugin) {
      return true;
    }

    if (storagePlugin instanceof FileSystemPlugin) {
      FileSystemPlugin fileSystemPlugin = (FileSystemPlugin) storagePlugin;
      return fileSystemPlugin.supportsIcebergTables();
    }

    return storagePlugin instanceof MutablePlugin;
  }

  @VisibleForTesting
  public void validateTableFormatOptions(SourceCatalog sourceCatalog, NamespaceKey path, OptionManager options) {
    boolean isStorageOptionIceberg = isStorageOptionIceberg(options, storageOptionsMap);
    if (isStorageOptionIceberg) {
      resetStorageOptions();
    }
    isIcebergTable = isIcebergDMLFeatureEnabled(sourceCatalog, path, options, storageOptionsMap) &&
      validatePluginSupportForIceberg(sourceCatalog, path);
    if (!isIcebergTable && isStorageOptionIceberg) {
      throw UserException.validationError()
        .message("Please enable required support options to perform operations in iceberg format", path.toString())
        .build(logger);
    }
  }

  private void resetStorageOptions() {
    storageOptionsMap = null;
  }

  public static void validateCreateTableLocation(SourceCatalog sourceCatalog, NamespaceKey path, SqlCreateEmptyTable sqlCreateEmptyTable) {
    if (sqlCreateEmptyTable.getLocation() != null) {
      StoragePlugin storagePlugin = sourceCatalog.getSource(path.getRoot());
      if (storagePlugin instanceof FileSystemPlugin) {
        throw UserException.parseError().message("LOCATION clause is not supported in the query for this source").build(logger);
      }
    }
  }

  @VisibleForTesting
  public void validateVersionedTableFormatOptions(SourceCatalog sourceCatalog, NamespaceKey path, OptionManager options) {
    isIcebergTable = validatePluginSupportForVersionedIceberg(sourceCatalog, path);
  }

  private  boolean validatePluginSupportForVersionedIceberg(SourceCatalog sourceCatalog, NamespaceKey path) {
    StoragePlugin versionedPlugin;
    try {
      versionedPlugin = sourceCatalog.getSource(path.getRoot());
    } catch (UserException uex) {
      return false;
    }

    if (!(versionedPlugin instanceof VersionedPlugin)) {
      return false;
    }
    isVersionedTable = true;
    return true;
  }

  @Override
  public String getTextPlan() {
    return textPlan;
  }
}
