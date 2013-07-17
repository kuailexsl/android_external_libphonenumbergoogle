/*
 * Copyright (C) 2009 The Libphonenumber Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.i18n.phonenumbers;

import com.google.i18n.phonenumbers.Phonemetadata.PhoneMetadata;
import com.google.i18n.phonenumbers.Phonemetadata.PhoneMetadataCollection;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Writer;
import java.util.Formatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tool to convert phone number metadata from the XML format to protocol buffer format.
 *
 * @author Shaopeng Jia
 */
public class BuildMetadataProtoFromXml extends Command {
  private static final String CLASS_NAME = BuildMetadataProtoFromXml.class.getSimpleName();
  private static final String PACKAGE_NAME = BuildMetadataProtoFromXml.class.getPackage().getName();

  // Command line parameter names.
  private static final String INPUT_FILE = "input-file";
  private static final String OUTPUT_DIR = "output-dir";
  private static final String DATA_PREFIX = "data-prefix";
  private static final String MAPPING_CLASS = "mapping-class";
  private static final String COPYRIGHT = "copyright";
  private static final String LITE_BUILD = "lite-build";

  private static final String HELP_MESSAGE =
      "Usage: " + CLASS_NAME + " [OPTION]...\n" +
      "\n" +
      "  --" + INPUT_FILE + "=PATH     Read phone number metadata in XML format from PATH.\n" +
      "  --" + OUTPUT_DIR + "=PATH     Use PATH as the root directory for output files.\n" +
      "  --" + DATA_PREFIX +
          "=PATH    Use PATH (relative to " + OUTPUT_DIR + ") as the basename when\n" +
      "                        writing phone number metadata (one file per region) in\n" +
      "                        proto format.\n" +
      "  --" + MAPPING_CLASS + "=NAME  Store country code mappings in the class NAME, which\n" +
      "                        will be written to a file in " + OUTPUT_DIR + ".\n" +
      "  --" + COPYRIGHT + "=YEAR      Use YEAR in generated copyright headers.\n" +
      "\n" +
      "  [--" + LITE_BUILD + "=<true|false>]  Optional (default: false). In a lite build,\n" +
      "                               certain metadata will be omitted. At this\n" +
      "                               moment, example numbers information is omitted.\n" +
      "\n" +
      "Example command line invocation:\n" +
      CLASS_NAME + " \\\n" +
      "  --" + INPUT_FILE + "=resources/PhoneNumberMetadata.xml \\\n" +
      "  --" + OUTPUT_DIR + "=java/libphonenumber/src/com/google/i18n/phonenumbers \\\n" +
      "  --" + DATA_PREFIX + "=data/PhoneNumberMetadataProto \\\n" +
      "  --" + MAPPING_CLASS + "=CountryCodeToRegionCodeMap \\\n" +
      "  --" + COPYRIGHT + "=2010 \\\n" +
      "  --" + LITE_BUILD + "=false\n";

  private static final String GENERATION_COMMENT =
      "/* This file is automatically generated by {@link " + CLASS_NAME + "}.\n" +
      " * Please don't modify it directly.\n" +
      " */\n\n";

  @Override
  public String getCommandName() {
    return CLASS_NAME;
  }

  @Override
  public boolean start() {
    // The format of a well-formed command line parameter.
    Pattern pattern = Pattern.compile("--(.+?)=(.*)");

    String inputFile = null;
    String outputDir = null;
    String dataPrefix = null;
    String mappingClass = null;
    String copyright = null;
    boolean liteBuild = false;

    for (int i = 1; i < getArgs().length; i++) {
      String key = null;
      String value = null;
      Matcher matcher = pattern.matcher(getArgs()[i]);
      if (matcher.matches()) {
        key = matcher.group(1);
        value = matcher.group(2);
      }

      if (INPUT_FILE.equals(key)) {
        inputFile = value;
      } else if (OUTPUT_DIR.equals(key)) {
        outputDir = value;
      } else if (DATA_PREFIX.equals(key)) {
        dataPrefix = value;
      } else if (MAPPING_CLASS.equals(key)) {
        mappingClass = value;
      } else if (COPYRIGHT.equals(key)) {
        copyright = value;
      } else if (LITE_BUILD.equals(key) &&
                 ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value))) {
        liteBuild = "true".equalsIgnoreCase(value);
      } else {
        System.err.println(HELP_MESSAGE);
        System.err.println("Illegal command line parameter: " + getArgs()[i]);
        return false;
      }
    }

    if (inputFile == null ||
        outputDir == null ||
        dataPrefix == null ||
        mappingClass == null ||
        copyright == null) {
      System.err.println(HELP_MESSAGE);
      return false;
    }

    String filePrefix = new File(outputDir, dataPrefix).getPath();

    try {
      PhoneMetadataCollection metadataCollection =
          BuildMetadataFromXml.buildPhoneMetadataCollection(inputFile, liteBuild);

      for (PhoneMetadata metadata : metadataCollection.getMetadataList()) {
        String regionCode = metadata.getId();
        // For non-geographical country calling codes (e.g. +800), or for alternate formats, use the
        // country calling codes instead of the region code to form the file name.
        if (regionCode.equals("001") || regionCode.isEmpty()) {
          regionCode = Integer.toString(metadata.getCountryCode());
        }
        PhoneMetadataCollection outMetadataCollection = new PhoneMetadataCollection();
        outMetadataCollection.addMetadata(metadata);
        FileOutputStream outputForRegion = new FileOutputStream(filePrefix + "_" + regionCode);
        ObjectOutputStream out = new ObjectOutputStream(outputForRegion);
        outMetadataCollection.writeExternal(out);
        out.close();
      }

      Map<Integer, List<String>> countryCodeToRegionCodeMap =
          BuildMetadataFromXml.buildCountryCodeToRegionCodeMap(metadataCollection);

      writeCountryCallingCodeMappingToJavaFile(
          countryCodeToRegionCodeMap, outputDir, mappingClass, copyright);
    } catch (Exception e) {
      e.printStackTrace();
      return false;
    }
    System.out.println("Metadata code successfully generated.");
    return true;
  }

  private static final String MAP_COMMENT =
      "  // A mapping from a country code to the region codes which denote the\n" +
      "  // country/region represented by that country code. In the case of multiple\n" +
      "  // countries sharing a calling code, such as the NANPA countries, the one\n" +
      "  // indicated with \"isMainCountryForCode\" in the metadata should be first.\n";
  private static final String COUNTRY_CODE_SET_COMMENT =
      "  // A set of all country codes for which data is available.\n";
  private static final String REGION_CODE_SET_COMMENT =
      "  // A set of all region codes for which data is available.\n";
  private static final double CAPACITY_FACTOR = 0.75;
  private static final String CAPACITY_COMMENT =
      "    // The capacity is set to %d as there are %d different entries,\n" +
      "    // and this offers a load factor of roughly " + CAPACITY_FACTOR + ".\n";

  private static void writeCountryCallingCodeMappingToJavaFile(
      Map<Integer, List<String>> countryCodeToRegionCodeMap,
      String outputDir, String mappingClass, String copyright) throws IOException {
    // Find out whether the countryCodeToRegionCodeMap has any region codes or country
    // calling codes listed in it.
    boolean hasRegionCodes = false;
    for (List<String> listWithRegionCode : countryCodeToRegionCodeMap.values()) {
      if (!listWithRegionCode.isEmpty()) {
        hasRegionCodes = true;
        break;
      }
    }
    boolean hasCountryCodes = countryCodeToRegionCodeMap.size() > 1;

    ClassWriter writer = new ClassWriter(outputDir, mappingClass, copyright);

    int capacity = (int) (countryCodeToRegionCodeMap.size() / CAPACITY_FACTOR);
    if (hasRegionCodes && hasCountryCodes) {
      writeMap(writer, capacity, countryCodeToRegionCodeMap);
    } else if (hasCountryCodes) {
      writeCountryCodeSet(writer, capacity, countryCodeToRegionCodeMap.keySet());
    } else {
      List<String> regionCodeList = countryCodeToRegionCodeMap.get(0);
      capacity = (int) (regionCodeList.size() / CAPACITY_FACTOR);
      writeRegionCodeSet(writer, capacity, regionCodeList);
    }

    writer.writeToFile();
  }

  private static void writeMap(ClassWriter writer, int capacity,
                               Map<Integer, List<String>> countryCodeToRegionCodeMap) {
    writer.addToBody(MAP_COMMENT);

    writer.addToImports("java.util.ArrayList");
    writer.addToImports("java.util.HashMap");
    writer.addToImports("java.util.List");
    writer.addToImports("java.util.Map");

    writer.addToBody("  static Map<Integer, List<String>> getCountryCodeToRegionCodeMap() {\n");
    writer.formatToBody(CAPACITY_COMMENT, capacity, countryCodeToRegionCodeMap.size());
    writer.addToBody("    Map<Integer, List<String>> countryCodeToRegionCodeMap =\n");
    writer.addToBody("        new HashMap<Integer, List<String>>(" + capacity + ");\n");
    writer.addToBody("\n");
    writer.addToBody("    ArrayList<String> listWithRegionCode;\n");
    writer.addToBody("\n");

    for (Map.Entry<Integer, List<String>> entry : countryCodeToRegionCodeMap.entrySet()) {
      int countryCallingCode = entry.getKey();
      List<String> regionCodes = entry.getValue();
      writer.addToBody("    listWithRegionCode = new ArrayList<String>(" +
                       regionCodes.size() + ");\n");
      for (String regionCode : regionCodes) {
        writer.addToBody("    listWithRegionCode.add(\"" + regionCode + "\");\n");
      }
      writer.addToBody("    countryCodeToRegionCodeMap.put(" + countryCallingCode +
                       ", listWithRegionCode);\n");
      writer.addToBody("\n");
    }

    writer.addToBody("    return countryCodeToRegionCodeMap;\n");
    writer.addToBody("  }\n");
  }

  private static void writeRegionCodeSet(ClassWriter writer, int capacity,
                                         List<String> regionCodeList) {
    writer.addToBody(REGION_CODE_SET_COMMENT);

    writer.addToImports("java.util.HashSet");
    writer.addToImports("java.util.Set");

    writer.addToBody("  static Set<String> getRegionCodeSet() {\n");
    writer.formatToBody(CAPACITY_COMMENT, capacity, regionCodeList.size());
    writer.addToBody("    Set<String> regionCodeSet = new HashSet<String>(" + capacity + ");\n");
    writer.addToBody("\n");

    for (String regionCode : regionCodeList) {
      writer.addToBody("    regionCodeSet.add(\"" + regionCode + "\");\n");
    }

    writer.addToBody("\n");
    writer.addToBody("    return regionCodeSet;\n");
    writer.addToBody("  }\n");
  }

  private static void writeCountryCodeSet(ClassWriter writer, int capacity,
                                          Set<Integer> countryCodeSet) {
    writer.addToBody(COUNTRY_CODE_SET_COMMENT);

    writer.addToImports("java.util.HashSet");
    writer.addToImports("java.util.Set");

    writer.addToBody("  static Set<Integer> getCountryCodeSet() {\n");
    writer.formatToBody(CAPACITY_COMMENT, capacity, countryCodeSet.size());
    writer.addToBody("    Set<Integer> countryCodeSet = new HashSet<Integer>(" + capacity + ");\n");
    writer.addToBody("\n");

    for (int countryCallingCode : countryCodeSet) {
      writer.addToBody("    countryCodeSet.add(" + countryCallingCode + ");\n");
    }

    writer.addToBody("\n");
    writer.addToBody("    return countryCodeSet;\n");
    writer.addToBody("  }\n");
  }

  private static final class ClassWriter {
    private final String name;
    private final String copyright;

    private final SortedSet<String> imports;
    private final StringBuffer body;
    private final Formatter formatter;
    private final Writer writer;

    ClassWriter(String outputDir, String name, String copyright) throws IOException {
      this.name = name;
      this.copyright = copyright;

      imports = new TreeSet<String>();
      body = new StringBuffer();
      formatter = new Formatter(body);
      writer = new BufferedWriter(new FileWriter(new File(outputDir, name + ".java")));
    }

    void addToImports(String name) {
      imports.add(name);
    }

    void addToBody(CharSequence text) {
      body.append(text);
    }

    void formatToBody(String format, Object... args) {
      formatter.format(format, args);
    }

    void writeToFile() throws IOException {
      CopyrightNotice.writeTo(writer, Integer.valueOf(copyright));
      writer.write(GENERATION_COMMENT);
      writer.write("package " + PACKAGE_NAME + ";\n\n");

      if (!imports.isEmpty()) {
        for (String item : imports) {
          writer.write("import " + item + ";\n");
        }
        writer.write("\n");
      }

      writer.write("public class " + name + " {\n");
      writer.write(body.toString());
      writer.write("}\n");

      writer.flush();
      writer.close();
    }
  }
}
