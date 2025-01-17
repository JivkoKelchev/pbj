// SPDX-License-Identifier: Apache-2.0
package com.hedera.pbj.compiler.impl.generators;

import static com.hedera.pbj.compiler.impl.Common.DEFAULT_INDENT;
import static com.hedera.pbj.compiler.impl.Common.camelToUpperSnake;
import static com.hedera.pbj.compiler.impl.Common.cleanDocStr;
import static com.hedera.pbj.compiler.impl.Common.cleanJavaDocComment;
import static com.hedera.pbj.compiler.impl.Common.getFieldsHashCode;
import static com.hedera.pbj.compiler.impl.Common.getJavaFile;
import static com.hedera.pbj.compiler.impl.Common.javaPrimitiveToObjectType;
import static com.hedera.pbj.compiler.impl.generators.EnumGenerator.EnumValue;
import static com.hedera.pbj.compiler.impl.generators.EnumGenerator.createEnum;
import static java.util.stream.Collectors.toMap;

import com.hedera.pbj.compiler.impl.Common;
import com.hedera.pbj.compiler.impl.ContextualLookupHelper;
import com.hedera.pbj.compiler.impl.Field;
import com.hedera.pbj.compiler.impl.Field.FieldType;
import com.hedera.pbj.compiler.impl.FileType;
import com.hedera.pbj.compiler.impl.MapField;
import com.hedera.pbj.compiler.impl.OneOfField;
import com.hedera.pbj.compiler.impl.SingleField;
import com.hedera.pbj.compiler.impl.grammar.Protobuf3Parser;
import com.hedera.pbj.compiler.impl.grammar.Protobuf3Parser.MessageDefContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Code generator that parses protobuf files and generates nice Java source for record files for each message type and
 * enum.
 */
@SuppressWarnings({"StringConcatenationInLoop", "EscapedSpace"})
public final class ModelGenerator implements Generator {

    private static final String NON_NULL_ANNOTATION = "@NonNull";

    private static final String HASH_CODE_MANIPULATION =
            """
            // Shifts: 30, 27, 16, 20, 5, 18, 10, 24, 30
            hashCode += hashCode << 30;
            hashCode ^= hashCode >>> 27;
            hashCode += hashCode << 16;
            hashCode ^= hashCode >>> 20;
            hashCode += hashCode << 5;
            hashCode ^= hashCode >>> 18;
            hashCode += hashCode << 10;
            hashCode ^= hashCode >>> 24;
            hashCode += hashCode << 30;
            """.indent(DEFAULT_INDENT);

    /**
     * Generating method that assembles all the previously generated pieces together
     *
     * @param modelPackage the model package to use for the code generation
     * @param imports the imports to use for the code generation
     * @param javaDocComment the java doc comment to use for the code generation
     * @param deprecated the deprecated annotation to add
     * @param javaRecordName the name of the class
     * @param fields the fields to use for the code generation
     * @param bodyContent the body content to use for the code generation
     *
     * @return the generated code
     */
    @NonNull
    private static String generateClass(final String modelPackage,
            final Set<String> imports,
            final String javaDocComment,
            final String deprecated,
            final String javaRecordName,
            final List<Field> fields,
            final String bodyContent,
            final boolean isComparable) {
        final String implementsComparable;
        if (isComparable) {
            imports.add("java.lang.Comparable");
            implementsComparable = "implements Comparable<$javaRecordName> ";
        } else {
            implementsComparable = "";
        }
        // spotless:off
        return """
               package $package;
               $imports
               import com.hedera.pbj.runtime.Codec;
               import java.util.function.Consumer;
               import edu.umd.cs.findbugs.annotations.Nullable;
               import edu.umd.cs.findbugs.annotations.NonNull;
               import static java.util.Objects.requireNonNull;
               
               $javaDocComment$deprecated
               public record $javaRecordName(
               $fields) $implementsComparable{
               $bodyContent}
               """
                .replace("$package", modelPackage)
                .replace("$imports", imports.isEmpty() ? ""
                        : imports.stream().collect(Collectors.joining(".*;\nimport ", "\nimport ", ".*;\n")))
                .replace("$javaDocComment", javaDocComment)
                .replace("$deprecated", deprecated)
                .replace("$implementsComparable", implementsComparable)
                .replace("$javaRecordName", javaRecordName)
                .replace("$fields", fields.stream().map(field -> "%s%s %s"
                        .formatted(getFieldAnnotations(field), field.javaFieldType(), field.nameCamelFirstLower()))
                        .collect(Collectors.joining(",\n")).indent(DEFAULT_INDENT))
                .replace("$bodyContent", bodyContent);
        // spotless:on
    }

    /**
     * Returns a set of annotations for a given field.
     *
     * @param field a field
     *
     * @return an empty string, or a string with Java annotations ending with a space
     */
    private static String getFieldAnnotations(final Field field) {
        if (field.repeated()) {
            return NON_NULL_ANNOTATION + " ";
        }
        return switch (field.type()) {
            case MESSAGE -> "@Nullable ";
            case BYTES, STRING -> NON_NULL_ANNOTATION + " ";
            default -> "";
        };
    }

    /**
     * Filter the fields to only include those that are comparable
     *
     * @param msgDef The message definition
     * @param lookupHelper The lookup helper
     * @param fields The fields to filter
     *
     * @return the filtered fields
     */
    @NonNull
    private static List<Field> filterComparableFields(final MessageDefContext msgDef,
            final ContextualLookupHelper lookupHelper,
            final List<Field> fields) {
        final Map<String, Field> fieldByName = fields.stream().collect(toMap(Field::name, f -> f));
        final List<String> comparableFields = lookupHelper.getComparableFields(msgDef);
        return comparableFields.stream().map(fieldByName::get).collect(Collectors.toList());
    }

    /**
     * Generates the compareTo method
     *
     * @param fields the fields to use for the code generation
     * @param javaRecordName the name of the class
     * @param destinationSrcDir the destination source directory
     *
     * @return the generated code
     */
    @NonNull
    private static String generateCompareTo(final List<Field> fields, final String javaRecordName,
            final File destinationSrcDir) {
        // spotless:off
        String bodyContent =
                """
                /**
                 * Implementation of Comparable interface
                 */
                @Override
                public int compareTo($javaRecordName thatObj) {
                    if (thatObj == null) {
                        return 1;
                    }
                    int result = 0;
                """.replace("$javaRecordName", javaRecordName).indent(DEFAULT_INDENT);
        bodyContent += Common.getFieldsCompareToStatements(fields, "", destinationSrcDir);
        bodyContent +=
                """
                    return result;
                }
                """.indent(DEFAULT_INDENT);
        // spotless:on
        return bodyContent;
    }

    /**
     * Generates the equals method
     *
     * @param fields the fields to use for the code generation
     * @param javaRecordName the name of the class
     *
     * @return the generated code
     */
    @NonNull
    private static String generateEquals(final List<Field> fields, final String javaRecordName) {
        String equalsStatements = "";
        // Generate a call to private method that iterates through fields
        // and calculates the hashcode.
        equalsStatements = Common.getFieldsEqualsStatements(fields, equalsStatements);
        // spotless:off
        String bodyContent =
                """
                /**
                * Override the default equals method for
                */
                @Override
                public boolean equals(Object that) {
                    if (that == null || this.getClass() != that.getClass()) {
                        return false;
                    }
                    $javaRecordName thatObj = ($javaRecordName)that;
                """.replace("$javaRecordName", javaRecordName).indent(DEFAULT_INDENT);
        bodyContent += equalsStatements.indent(DEFAULT_INDENT);
        bodyContent +=
                """
                    return true;
                }""".indent(DEFAULT_INDENT);
        // spotless:on
        return bodyContent;
    }

    /**
     * Generates the hashCode method
     *
     * @param fields the fields to use for the code generation
     *
     * @return the generated code
     */
    @NonNull
    private static String generateHashCode(final List<Field> fields) {
        // Generate a call to private method that iterates through fields and calculates the hashcode
        final String statements = getFieldsHashCode(fields, "");
        // spotless:off
        String bodyContent =
                """
                /**
                 * Override the default hashCode method for
                 * all other objects to make hashCode
                 */
                @Override
                public int hashCode() {
                    int result = 1;
                """.indent(DEFAULT_INDENT);
        bodyContent += statements;
        bodyContent +=
                """
                    long hashCode = result;
                $hashCodeManipulation
                    return (int)hashCode;
                }
                """.replace("$hashCodeManipulation", HASH_CODE_MANIPULATION).indent(DEFAULT_INDENT);
        // spotless:on
        return bodyContent;
    }

    /**
     * Generates a pre-populated constructor for a class.
     *
     * @param fields the fields to use for the code generation
     *
     * @return the generated code
     */
    private static String generateConstructor(
            final String constructorName,
            final List<Field> fields,
            final boolean shouldThrowOnOneOfNull,
            final MessageDefContext msgDef,
            final ContextualLookupHelper lookupHelper) {
        if (fields.isEmpty()) {
            return "";
        }
        // spotless:off
        return """
                   /**
                    * Create a pre-populated $constructorName.
                    * $constructorParamDocs
                    */
                   public $constructorName($constructorParams) {
               $constructorCode    }
               """
                .replace("$constructorParamDocs", fields.stream().map(field ->
                        "\n     * @param %s %s".formatted(field.nameCamelFirstLower(),
                        field.comment().replaceAll("\n", "\n     *         %s"
                                .formatted(" ".repeat(field.nameCamelFirstLower().length()))))
                ).collect(Collectors.joining(", ")))
                .replace("$constructorName", constructorName)
                .replace("$constructorParams", fields.stream().map(field ->
                        "%s %s".formatted(field.javaFieldType(), field.nameCamelFirstLower())
                ).collect(Collectors.joining(", ")))
                .replace("$constructorCode", fields.stream().map(field -> {
                    StringBuilder sb = new StringBuilder();
                    if (shouldThrowOnOneOfNull && field instanceof OneOfField) {
                        sb.append(generateConstructorCodeForField(field)).append('\n');
                    }
                    switch (field.type()) {
                        case BYTES, STRING: {
                            sb.append("this.$name = $name != null ? $name : $default;"
                                    .replace("$name", field.nameCamelFirstLower())
                                    .replace("$default", getDefaultValue(field, msgDef, lookupHelper))
                            );
                            break;
                        }
                        case MAP: {
                            sb.append("this.$name = PbjMap.of($name);"
                                    .replace("$name", field.nameCamelFirstLower())
                            );
                            break;
                        }
                        default:
                            if (field.repeated()) {
                                sb.append(
                                        "this.$name = $name == null ? Collections.emptyList() : $name;"
                                                .replace("$name", field.nameCamelFirstLower()));
                            } else {
                                sb.append("this.$name = $name;".replace("$name", field.nameCamelFirstLower()));
                            }
                            break;
                    }
                    return sb.toString();
                }).collect(Collectors.joining("\n")).indent(DEFAULT_INDENT * 2));
        // spotless:on
    }

    /**
     * Generates the constructor code for the class
     *
     * @param f the field to use for the code generation
     *
     * @return the generated code
     */
    private static String generateConstructorCodeForField(final Field f) {
        // spotless:off
        final StringBuilder sb = new StringBuilder(
                """
                if ($fieldName == null) {
                    throw new NullPointerException("Parameter '$fieldName' must be supplied and can not be null");
                }""".replace("$fieldName", f.nameCamelFirstLower()));
        if (f instanceof final OneOfField oof) {
            for (final Field subField : oof.fields()) {
                if (subField.optionalValueType()) {
                    sb.append("""
                              
                              // handle special case where protobuf does not have destination between a OneOf with optional
                              // value of empty vs an unset OneOf.
                              if ($fieldName.kind() == $fieldUpperNameOneOfType.$subFieldNameUpper && $fieldName.value() == null) {
                                  $fieldName = new $className<>($fieldUpperNameOneOfType.UNSET, null);
                              }"""
                            .replace("$className", oof.className())
                            .replace("$fieldName", f.nameCamelFirstLower())
                            .replace("$fieldUpperName", f.nameCamelFirstUpper())
                            .replace("$subFieldNameUpper", camelToUpperSnake(subField.name()))
                    );
                }
            }
        }
        // spotless:on
        return sb.toString().indent(DEFAULT_INDENT);
    }

    /**
     * Generates codec fields for the calss
     *
     * @param msgDef the message definition
     * @param lookupHelper the lookup helper
     * @param javaRecordName the name of the class
     *
     * @return the generated code
     */
    @NonNull
    private static String generateCodecFields(final MessageDefContext msgDef, final ContextualLookupHelper lookupHelper,
            final String javaRecordName) {
        // spotless:off
        return """
               /** Protobuf codec for reading and writing in protobuf format */
               public static final Codec<$modelClass> PROTOBUF = new $qualifiedCodecClass();
               /** JSON codec for reading and writing in JSON format */
               public static final JsonCodec<$modelClass> JSON = new $qualifiedJsonCodecClass();
               
               /** Default instance with all fields set to default values */
               public static final $modelClass DEFAULT = newBuilder().build();
               """
                .replace("$modelClass", javaRecordName)
                .replace("$qualifiedCodecClass", lookupHelper.getFullyQualifiedMessageClassname(FileType.CODEC, msgDef))
                .replace("$qualifiedJsonCodecClass",
                        lookupHelper.getFullyQualifiedMessageClassname(FileType.JSON_CODEC, msgDef))
                .indent(DEFAULT_INDENT);
        // spotless:on
    }

    /**
     * Generates accessor fields for the class
     *
     * @param item message element context provided by the parser
     * @param fields the fields to use for the code generation
     * @param imports the imports to use for the code generation
     * @param hasMethods the has methods to use for the code generation
     */
    private static void generateCodeForField(final ContextualLookupHelper lookupHelper,
            final Protobuf3Parser.MessageElementContext item,
            final List<Field> fields,
            final Set<String> imports,
            final List<String> hasMethods) {
        final SingleField field = new SingleField(item.field(), lookupHelper);
        fields.add(field);
        field.addAllNeededImports(imports, true, false, false);
        // Note that repeated fields default to empty list, so technically they always have a non-null value,
        // and therefore the additional convenience methods, especially when they throw an NPE, don't make sense.
        // spotless:off
        if (field.type() == FieldType.MESSAGE && !field.repeated()) {
            hasMethods.add(
                    """
                    /**
                     * Convenience method to check if the $fieldName has a value
                     *
                     * @return true of the $fieldName has a value
                     */
                    public boolean has$fieldNameUpperFirst() {
                     return $fieldName != null;
                    }

                    /**
                     * Gets the value for $fieldName if it has a value, or else returns the default
                     * value for the type.
                     *
                     * @param defaultValue the default value to return if $fieldName is null
                     * @return the value for $fieldName if it has a value, or else returns the default value
                     */
                    public $javaFieldType $fieldNameOrElse(@NonNull final $javaFieldType defaultValue) {
                     return has$fieldNameUpperFirst() ? $fieldName : defaultValue;
                    }

                    /**
                     * Gets the value for $fieldName if it has a value, or else throws an NPE.
                     * value for the type.
                     *
                     * @return the value for $fieldName if it has a value
                     * @throws NullPointerException if $fieldName is null
                     */
                    public @NonNull $javaFieldType $fieldNameOrThrow() {
                     return requireNonNull($fieldName, "Field $fieldName is null");
                    }

                    /**
                     * Executes the supplied {@link Consumer} if, and only if, the $fieldName has a value
                     *
                     * @param ifPresent the {@link Consumer} to execute
                     */
                    public void if$fieldNameUpperFirst(@NonNull final Consumer<$javaFieldType> ifPresent) {
                     if (has$fieldNameUpperFirst()) {
                         ifPresent.accept($fieldName);
                     }
                    }
                    """
                    .replace("$fieldNameUpperFirst", field.nameCamelFirstUpper())
                    .replace("$javaFieldType", field.javaFieldType())
                    .replace("$fieldName", field.nameCamelFirstLower())
                    .indent(DEFAULT_INDENT));
        }
        // spotless:on
    }

    /**
     * Generates the code related to the oneof field
     *
     * @param lookupHelper the lookup helper
     * @param item message element context provided by the parser
     * @param javaRecordName the name of the class
     * @param imports the imports to use for the code generation
     * @param oneofEnums the oneof enums to use for the code generation
     * @param fields the fields to use for the code generation
     *
     * @return the generated code
     */
    private static List<String> generateCodeForOneOf(final ContextualLookupHelper lookupHelper,
            final Protobuf3Parser.MessageElementContext item,
            final String javaRecordName,
            final Set<String> imports,
            final List<String> oneofEnums,
            final List<Field> fields) {
        final List<String> oneofGetters = new ArrayList<>();
        final var oneOfField = new OneOfField(item.oneof(), javaRecordName, lookupHelper);
        final var enumName = oneOfField.nameCamelFirstUpper() + "OneOfType";
        final int maxIndex = oneOfField.fields().getLast().fieldNumber();
        final Map<Integer, EnumValue> enumValues = new HashMap<>();
        // spotless:off
        for (final var field : oneOfField.fields()) {
            final String javaFieldType = javaPrimitiveToObjectType(field.javaFieldType());
            final String enumComment = cleanDocStr(field.comment())
                    .replaceAll("[\t\s]*/\\*\\*", "") // remove doc start indenting
                    .replaceAll("\n[\t\s]+\\*", "\n") // remove doc indenting
                    .replaceAll("/\\*\\*", "") //  remove doc start
                    .replaceAll("\\*\\*/", ""); //  remove doc end
            enumValues.put(field.fieldNumber(), new EnumValue(field.name(), field.deprecated(), enumComment));
            // generate getters for one ofs
            oneofGetters.add(
                    """
                    /**
                     * Direct typed getter for one of field $fieldName.
                     *
                     * @return one of value or null if one of is not set or a different one of value
                     */
                    public @Nullable $javaFieldType $fieldName() {
                       return $oneOfField.kind() == $enumName.$enumValue ? ($javaFieldType)$oneOfField.value() : null;
                    }

                    /**
                     * Convenience method to check if the $oneOfField has a one-of with type $enumValue
                     *
                     * @return true of the one of kind is $enumValue
                     */
                    public boolean has$fieldNameUpperFirst() {
                       return $oneOfField.kind() == $enumName.$enumValue;
                    }

                    /**
                     * Gets the value for $fieldName if it has a value, or else returns the default
                     * value for the type.
                     *
                     * @param defaultValue the default value to return if $fieldName is null
                     * @return the value for $fieldName if it has a value, or else returns the default value
                     */
                    public $javaFieldType $fieldNameOrElse(@NonNull final $javaFieldType defaultValue) {
                       return has$fieldNameUpperFirst() ? $fieldName() : defaultValue;
                    }

                    /**
                     * Gets the value for $fieldName if it was set, or throws a NullPointerException if it was not set.
                     *
                     * @return the value for $fieldName if it has a value
                     * @throws NullPointerException if $fieldName is null
                     */
                    public @NonNull $javaFieldType $fieldNameOrThrow() {
                       return requireNonNull($fieldName(), "Field $fieldName is null");
                    }
                    """
                    .replace("$fieldNameUpperFirst", field.nameCamelFirstUpper())
                    .replace("$fieldName", field.nameCamelFirstLower())
                    .replace("$javaFieldType", javaFieldType)
                    .replace("$oneOfField", oneOfField.nameCamelFirstLower())
                    .replace("$enumName", enumName)
                    .replace("$enumValue", camelToUpperSnake(field.name()))
                    .indent(DEFAULT_INDENT)
            );
            if (field.type() == FieldType.MESSAGE) {
                field.addAllNeededImports(imports, true, false, false);
            }
        }
        // spotless:on
        final String enumComment =
                """
                /**
                 * Enum for the type of "%s" oneof value
                 */""".formatted(oneOfField.name());
        final String enumString = createEnum(enumComment, "", enumName, maxIndex, enumValues, true)
                .indent(DEFAULT_INDENT * 2);
        oneofEnums.add(enumString);
        fields.add(oneOfField);
        imports.add("com.hedera.pbj.runtime");
        return oneofGetters;
    }

    @NonNull
    private static String genrateBuilderFactoryMethods(String bodyContent, final List<Field> fields) {
        // spotless:off
        bodyContent +=
                """
                /**
                 * Return a builder for building a copy of this model object. It will be pre-populated with all the data from this
                 * model object.
                 *
                 * @return a pre-populated builder
                 */
                public Builder copyBuilder() {
                    return new Builder(%s);
                }
                
                /**
                 * Return a new builder for building a model object. This is just a shortcut for <code>new Model.Builder()</code>.
                 *
                 * @return a new builder
                 */
                public static Builder newBuilder() {
                    return new Builder();
                }
                """
                .formatted(fields.stream().map(Field::nameCamelFirstLower).collect(Collectors.joining(", ")))
                .indent(DEFAULT_INDENT);
        // spotless:on
        return bodyContent;
    }

    private static void generateBuilderMethods(
            final List<String> builderMethods,
            final MessageDefContext msgDef,
            final Field field,
            final ContextualLookupHelper lookupHelper) {
        final String prefix, postfix, fieldToSet;
        final String fieldAnnotations = getFieldAnnotations(field);
        final OneOfField parentOneOfField = field.parent();
        final String fieldName = field.nameCamelFirstLower();
        if (parentOneOfField != null) {
            final String oneOfEnumValue = "%s.%s"
                    .formatted(parentOneOfField.getEnumClassRef(), camelToUpperSnake(field.name()));
            prefix = "%s%s,".formatted(" new %s<>(".formatted(parentOneOfField.className()), oneOfEnumValue);
            postfix = ")";
            fieldToSet = parentOneOfField.nameCamelFirstLower();
        } else if (fieldAnnotations.contains(NON_NULL_ANNOTATION)) {
            prefix = "";
            postfix = " != null ? %s : %s".formatted(fieldName, getDefaultValue(field, msgDef, lookupHelper));
            fieldToSet = fieldName;
        } else {
            prefix = "";
            postfix = "";
            fieldToSet = fieldName;
        }
        // spotless:off
        builderMethods.add(
                """
                /**
                 * $fieldDoc
                 *
                 * @param $fieldName value to set
                 * @return builder to continue building with
                 */
                public Builder $fieldName($fieldAnnotations$fieldType $fieldName) {
                    this.$fieldToSet = $prefix$fieldName$postfix;
                    return this;
                }"""
                .replace("$fieldDoc", field.comment()
                        .replaceAll("\n", "\n * "))
                .replace("$fieldName", fieldName)
                .replace("$fieldToSet", fieldToSet)
                .replace("$prefix", prefix)
                .replace("$postfix", postfix)
                .replace("$fieldAnnotations", fieldAnnotations)
                .replace("$fieldType", field.javaFieldType())
                .indent(DEFAULT_INDENT)
        );
        // add nice method for simple message fields so can just set using un-built builder
        if (field.type() == Field.FieldType.MESSAGE && !field.optionalValueType() && !field.repeated()) {
            builderMethods.add(
                    """
                    /**
                     * $fieldDoc
                     *
                     * @param builder A pre-populated builder
                     * @return builder to continue building with
                     */
                    public Builder $fieldName($messageClass.Builder builder) {
                        this.$fieldToSet =$prefix builder.build() $postfix;
                        return this;
                    }"""
                    .replace("$messageClass", field.messageType())
                    .replace("$fieldDoc", field.comment()
                            .replaceAll("\n", "\n * "))
                    .replace("$fieldName", fieldName)
                    .replace("$fieldToSet", fieldToSet)
                    .replace("$prefix", prefix)
                    .replace("$postfix", postfix)
                    .replace("$fieldType", field.javaFieldType())
                    .indent(DEFAULT_INDENT)
            );
            // spotless:on
        }

        // add nice method for message fields with list types for varargs
        if (field.repeated()) {
            // Need to re-define the prefix and postfix for repeated fields because they don't use `values` directly
            // but wrap it in List.of(values) instead, so the simple definitions above don't work here.
            final String repeatedPrefix;
            final String repeatedPostfix;
            // spotless:off
            if (parentOneOfField != null) {
                repeatedPrefix = "%s values == null ? %s : "
                        .formatted(prefix, getDefaultValue(field, msgDef, lookupHelper));
                repeatedPostfix = postfix;
            } else if (fieldAnnotations.contains(NON_NULL_ANNOTATION)) {
                repeatedPrefix = "values == null ? %s : "
                        .formatted(getDefaultValue(field, msgDef, lookupHelper));
                repeatedPostfix = "";
            } else {
                repeatedPrefix = prefix;
                repeatedPostfix = postfix;
            }
            builderMethods.add(
                    """
                    /**
                     * $fieldDoc
                     *
                     * @param values varargs value to be built into a list
                     * @return builder to continue building with
                     */
                    public Builder $fieldName($baseType ... values) {
                        this.$fieldToSet = $repeatedPrefix List.of(values) $repeatedPostfix;
                        return this;
                    }"""
                    .replace("$baseType",
                            field.javaFieldType().substring("List<".length(), field.javaFieldType().length() - 1))
                    .replace("$fieldDoc", field.comment()
                            .replaceAll("\n", "\n * "))
                    .replace("$fieldName", fieldName)
                    .replace("$fieldToSet", fieldToSet)
                    .replace("$fieldType", field.javaFieldType())
                    .replace("$repeatedPrefix", repeatedPrefix)
                    .replace("$repeatedPostfix", repeatedPostfix)
                    .indent(DEFAULT_INDENT)
            );
            // spotless:on
        }
    }

    /**
     * Generates the builder for the class
     *
     * @param msgDef the message definition
     * @param fields the fields to use for the code generation
     * @param lookupHelper the lookup helper
     *
     * @return the generated code
     */
    private static String generateBuilder(final MessageDefContext msgDef, final List<Field> fields,
            final ContextualLookupHelper lookupHelper) {
        final String javaRecordName = msgDef.messageName().getText();
        final List<String> builderMethods = new ArrayList<>();
        for (final Field field : fields) {
            if (field.type() == Field.FieldType.ONE_OF) {
                final OneOfField oneOfField = (OneOfField)field;
                for (final Field subField : oneOfField.fields()) {
                    generateBuilderMethods(builderMethods, msgDef, subField, lookupHelper);
                }
            } else {
                generateBuilderMethods(builderMethods, msgDef, field, lookupHelper);
            }
        }
        // spotless:off
        return """
               /**
                * Builder class for easy creation, ideal for clean code where performance is not critical. In critical performance
                * paths use the constructor directly.
                */
               public static final class Builder {
                   $fields;
               
                   /**
                    * Create an empty builder
                    */
                   public Builder() {}
               
               $prePopulatedBuilder
                   /**
                    * Build a new model record with data set on builder
                    *
                    * @return new model record with data set
                    */
                   public $javaRecordName build() {
                       return new $javaRecordName($recordParams);
                   }
               
               $builderMethods}"""
                .replace("$fields", fields.stream().map(field -> "%sprivate %s %s = %s"
                        .formatted(getFieldAnnotations(field), field.javaFieldType(), field.nameCamelFirstLower(),
                                getDefaultValue(field, msgDef, lookupHelper)))
                .collect(Collectors.joining(";\n    ")))
                .replace("$prePopulatedBuilder", generateConstructor("Builder", fields, false, msgDef, lookupHelper))
                .replace("$javaRecordName", javaRecordName)
                .replace("$recordParams",
                        fields.stream().map(Field::nameCamelFirstLower).collect(Collectors.joining(", ")))
                .replace("$builderMethods", String.join("\n", builderMethods))
                .indent(DEFAULT_INDENT);
        // spotless:on
    }

    /**
     * Gets the default value for the field
     *
     * @param field the field to use for the code generation
     * @param msgDef the message definition
     * @param lookupHelper the lookup helper
     *
     * @return the generated code
     */
    private static String getDefaultValue(final Field field, final MessageDefContext msgDef,
            final ContextualLookupHelper lookupHelper) {
        if (field.type() == Field.FieldType.ONE_OF) {
            return lookupHelper.getFullyQualifiedMessageClassname(FileType.CODEC, msgDef) + "." + field.javaDefault();
        } else {
            return field.javaDefault();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Generates a new model object, as a Java Record type.
     */
    public void generate(final MessageDefContext msgDef,
            final File destinationSrcDir,
            final File destinationTestSrcDir, final ContextualLookupHelper lookupHelper) throws IOException {

        // The javaRecordName will be something like "AccountID".
        final var javaRecordName = lookupHelper.getUnqualifiedClassForMessage(FileType.MODEL, msgDef);
        // The modelPackage is the Java package to put the model class into.
        final String modelPackage = lookupHelper.getPackageForMessage(FileType.MODEL, msgDef);
        // The File to write the sources that we generate into
        final File javaFile = getJavaFile(destinationSrcDir, modelPackage, javaRecordName);
        // The Javadoc "@Deprecated" tag, which is set if the protobuf schema says the field is deprecated
        String deprecated = "";
        // The list of fields, as defined in the protobuf schema
        final List<Field> fields = new ArrayList<>();
        // The generated Java code for an enum field if OneOf is used
        final List<String> oneofEnums = new ArrayList<>();
        // The generated Java code for getters if OneOf is used
        final List<String> oneofGetters = new ArrayList<>();
        // The generated Java code for has methods for normal fields
        final List<String> hasMethods = new ArrayList<>();
        // The generated Java import statements. We'll build this up as we go.
        final Set<String> imports = new TreeSet<>();
        imports.add("com.hedera.pbj.runtime");
        imports.add("com.hedera.pbj.runtime.io");
        imports.add("com.hedera.pbj.runtime.io.buffer");
        imports.add("com.hedera.pbj.runtime.io.stream");
        imports.add("edu.umd.cs.findbugs.annotations");

        // Iterate over all the items in the protobuf schema
        for (final var item : msgDef.messageBody().messageElement()) {
            if (item.messageDef() != null) { // process sub messages
                generate(item.messageDef(), destinationSrcDir, destinationTestSrcDir, lookupHelper);
            } else if (item.oneof() != null) { // process one ofs
                oneofGetters.addAll(
                        generateCodeForOneOf(lookupHelper, item, javaRecordName, imports, oneofEnums, fields));
            } else if (item.mapField() != null) { // process map fields
                final MapField field = new MapField(item.mapField(), lookupHelper);
                fields.add(field);
                field.addAllNeededImports(imports, true, false, false);
            } else if (item.field() != null && item.field().fieldName() != null) {
                generateCodeForField(lookupHelper, item, fields, imports, hasMethods);
            } else if (item.optionStatement() != null) {
                if ("deprecated".equals(item.optionStatement().optionName().getText())) {
                    deprecated = "@Deprecated ";
                } else {
                    System.err.printf("Unhandled Option: %s%n", item.optionStatement().getText());
                }
            } else if (item.reserved() == null) { // ignore reserved and warn about anything else
                System.err.printf("ModelGenerator Warning - Unknown element: %s -- %s%n", item, item.getText());
            }
        }

        // process field java doc and insert into record java doc

        // The javadoc comment to use for the model class, which comes **directly** from the protobuf schema,
        // but is cleaned up and formatted for use in JavaDoc.
        final String docComment = (msgDef.docComment() == null || msgDef.docComment().getText().isBlank())
                ? javaRecordName :
                cleanJavaDocComment(msgDef.docComment().getText());
        String javaDocComment = "/**\n * " + docComment.replaceAll("\n", "\n * ");
        if (fields.isEmpty()) {
            javaDocComment += "\n */";
        } else {
            // spotless:off
            javaDocComment += "\n *";
            for (final var field : fields) {
                final int nameLength = field.nameCamelFirstLower().length();
                final String indentedComment = field.comment()
                        .replaceAll("\n", "\n *         %s".formatted(" ".repeat(nameLength)));
                javaDocComment += "\n * @param %s %s".formatted(field.nameCamelFirstLower(), indentedComment);
            }
            javaDocComment += "\n */";
            // spotless:on
        }

        // === Build Body Content
        String bodyContent = "";

        // static codec and default instance
        bodyContent += generateCodecFields(msgDef, lookupHelper, javaRecordName);
        bodyContent += "\n";

        // constructor
        bodyContent += generateConstructor(javaRecordName, fields, true, msgDef, lookupHelper);
        bodyContent += "\n";

        bodyContent += generateHashCode(fields);
        bodyContent += "\n";

        bodyContent += generateEquals(fields, javaRecordName);

        final List<Field> comparableFields = filterComparableFields(msgDef, lookupHelper, fields);
        final boolean hasComparableFields = !comparableFields.isEmpty();
        if (hasComparableFields) {
            bodyContent += generateCompareTo(comparableFields, javaRecordName, destinationSrcDir);
        }

        // Has methods
        bodyContent += String.join("\n", hasMethods);
        bodyContent += "\n";

        // oneof getters
        bodyContent += String.join("\n    ", oneofGetters);
        bodyContent += "\n";

        // builder copy & new builder methods
        bodyContent = genrateBuilderFactoryMethods(bodyContent, fields);
        bodyContent += "\n";

        // generate builder
        bodyContent += generateBuilder(msgDef, fields, lookupHelper);
        if (!oneofEnums.isEmpty()) {
            bodyContent += "\n";
        }

        // oneof enums
        bodyContent += String.join("\n    ", oneofEnums);

        // === Build file
        try (final FileWriter javaWriter = new FileWriter(javaFile)) {
            javaWriter.write(
                    generateClass(modelPackage, imports, javaDocComment, deprecated, javaRecordName, fields,
                            bodyContent, hasComparableFields)
            );
        }
    }

}
