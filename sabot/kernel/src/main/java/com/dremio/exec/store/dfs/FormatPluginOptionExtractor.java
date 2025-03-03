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
package com.dremio.exec.store.dfs;

import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.calcite.schema.Function;
import org.slf4j.Logger;

import com.dremio.common.exceptions.UserException;
import com.dremio.common.logical.FormatPluginConfig;
import com.dremio.common.logical.FormatPluginConfigBase;
import com.dremio.common.scanner.persistence.ScanResult;
import com.dremio.exec.store.SchemaConfig;
import com.dremio.service.namespace.TableInstance;
import com.dremio.service.namespace.TableInstance.TableParamDef;
import com.dremio.service.namespace.TableInstance.TableSignature;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 * manages format plugins options to define table macros
 */
public final class FormatPluginOptionExtractor {
  private static final Logger logger = org.slf4j.LoggerFactory.getLogger(FormatPluginOptionExtractor.class);

  private final Map<String, FormatPluginOptionsDescriptor> optionsByTypeName;

  /**
   * extracts the format plugin options based on the scanned implementations of {@link FormatPluginConfig}
   * @param scanResult
   */
  FormatPluginOptionExtractor(ScanResult scanResult) {
    Map<String, FormatPluginOptionsDescriptor> result = new HashMap<>();
    Set<Class<? extends FormatPluginConfig>> pluginConfigClasses = FormatPluginConfigBase.getSubTypes(scanResult);
    for (Class<? extends FormatPluginConfig> pluginConfigClass : pluginConfigClasses) {
      FormatPluginOptionsDescriptor optionsDescriptor = new FormatPluginOptionsDescriptor(pluginConfigClass);
      result.put(optionsDescriptor.typeName.toLowerCase(), optionsDescriptor);
    }
    this.optionsByTypeName = unmodifiableMap(result);
  }

  /**
   * @return the extracted options
   */
  @VisibleForTesting
  Collection<FormatPluginOptionsDescriptor> getOptions() {
    return optionsByTypeName.values();
  }

  /**
   * give a table name, returns function signatures to configure the FormatPlugin
   * @param tableName the name of the table (or table function in this context)
   * @return the available signatures
   */
  private List<TableSignature> getTableSignatures(String tableName) {
    List<TableSignature> result = new ArrayList<>();
    for (FormatPluginOptionsDescriptor optionsDescriptor : optionsByTypeName.values()) {
      TableSignature sig = optionsDescriptor.getTableSignature(tableName);
      result.add(sig);
    }
    return unmodifiableList(result);
  }

  /**
   * given a table function signature and the corresponding parameters
   * return the corresponding formatPlugin configuration
   * @param t the signature and parameters (it should be one of the signatures returned by {@link FormatPluginOptionExtractor#getTableSignatures(String)})
   * @return the config
   */
  public FormatPluginConfig createConfigForTable(TableInstance t) {
    if (!t.getSig().getParams().get(0).getName().equals("type")) {
      throw UserException.parseError()
        .message("unknown first param for %s", t.getSig())
        .addContext("table", t.getSig().getName())
        .build(logger);
    }
    String type = (String)t.getParams().get(0);
    if (type == null) {
      throw UserException.parseError()
          .message("type param must be present but was missing")
          .addContext("table", t.getSig().getName())
          .build(logger);
    }
    FormatPluginOptionsDescriptor optionsDescriptor = optionsByTypeName.get(type.toLowerCase());
    if (optionsDescriptor == null) {
      throw UserException.parseError()
          .message(
              "unknown type %s, expected one of %s",
              type, optionsByTypeName.keySet())
          .addContext("table", t.getSig().getName())
          .build(logger);
    }
    return optionsDescriptor.createConfigForTable(t);
  }

  /**
   * Create {@link FormatPluginConfig} instance based on given format options.
   * @param tableName Table name
   * @param formatOptions
   * @return
   */
  FormatPluginConfig createConfigForTable(final String tableName, final Map<String, Object> formatOptions) {
    final Object typeObject = formatOptions.get("type");
    if (typeObject == null || !(typeObject instanceof String)) {
      throw UserException.validationError()
          .message("Invalid format type in storage options")
          .addContext("Given options: ", formatOptions)
          .build(logger);
    }

    final String type = (String) typeObject;
    FormatPluginOptionsDescriptor optionsDescriptor = optionsByTypeName.get(type.toLowerCase());
    if (optionsDescriptor == null) {
      throw UserException.parseError()
          .message("unknown type %s, expected one of %s", type, optionsByTypeName.keySet())
          .addContext("Given options: %s", formatOptions)
          .build(logger);
    }

    final TableSignature tableSignature = optionsDescriptor.getTableSignature(tableName);

    // From the map of options, create an ordered list of values based on the signature
    final Map<String, Object> formatOptionsCopy = Maps.newHashMap(formatOptions);
    final List<Object> params = Lists.newArrayList();
    for(TableParamDef paramDef : tableSignature.getParams()) {
      final Object value = formatOptionsCopy.remove(paramDef.getName());
      params.add(value);
    }

    if (!formatOptionsCopy.isEmpty()) {
      throw UserException.validationError()
        .message("Unknown storage option(s): %s ", formatOptionsCopy)
        .build(logger);
    }

    return createConfigForTable(new TableInstance(tableSignature, params));
  }

  public List<Function> getFunctions(
    final List<String> tableSchemaPath,
    final FileSystemPlugin<?> plugin,
    final SchemaConfig schemaConfig
  ) {
    List<TableSignature> sigs = getTableSignatures(tableSchemaPath.get(tableSchemaPath.size() - 1));
    return FluentIterable.from(sigs).transform(new com.google.common.base.Function<TableSignature, Function>() {
      @Override
      public Function apply(TableSignature input) {
        return new WithOptionsTableMacro(tableSchemaPath, input, plugin, schemaConfig);
      }
    }).toList();
  }
}
