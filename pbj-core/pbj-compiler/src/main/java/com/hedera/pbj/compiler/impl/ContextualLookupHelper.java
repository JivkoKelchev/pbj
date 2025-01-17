// SPDX-License-Identifier: Apache-2.0
package com.hedera.pbj.compiler.impl;

import java.io.File;
import java.util.List;
import com.hedera.pbj.compiler.impl.grammar.Protobuf3Parser.EnumDefContext;
import com.hedera.pbj.compiler.impl.grammar.Protobuf3Parser.MessageDefContext;
import com.hedera.pbj.compiler.impl.grammar.Protobuf3Parser.MessageTypeContext;
import com.hedera.pbj.compiler.impl.grammar.Protobuf3Parser.FieldContext;
import com.hedera.pbj.compiler.impl.grammar.Protobuf3Parser.Type_Context;
import com.hedera.pbj.compiler.impl.grammar.Protobuf3Parser.OneofFieldContext;

/**
 * Wrapper around LookupHelper adding the context of which protobuf source file the lookup is happening within. This
 * makes it easy to carry the source files context for lookup so that the package and imports are correctly understood.
 */
public class ContextualLookupHelper {
    /** Lookup helper that we are delegating to */
    private final LookupHelper lookupHelper;
    /** The proto source file for context */
    private final File srcProtoFileContext;

    /**
     * Create a new ContextualLookupHelper delegating to {@code  lookupHelper} with the context of
     * {@code srcProtoFileContext}.
     *
     * @param lookupHelper Lookup helper that we are delegating to
     * @param srcProtoFileContext The proto source file for context
     */
    public ContextualLookupHelper(LookupHelper lookupHelper, File srcProtoFileContext) {
        this.lookupHelper = lookupHelper;
        this.srcProtoFileContext = srcProtoFileContext;
    }

    /**
     * Get the unqualified Java class name for given message or enum.
     *
     * @param fileType The type of file we want the package for
     * @param context Parser Context, a message or enum
     * @return java package to put model class in
     */
    public String getUnqualifiedClassForMessage(final FileType fileType, final MessageDefContext context) {
        return lookupHelper.getUnqualifiedClass(srcProtoFileContext, fileType, context);
    }

    /**
     * Get the fully qualified class name for a msgDef with given fileType that would be generated by PBJ.
     *
     * @param fileType The type of file we want the fully qualified class name for
     * @param message The msgDef to get fully qualified class name for
     * @return fully qualified class name
     */
    public String getFullyQualifiedMessageClassname(final FileType fileType, final MessageDefContext message) {
        return lookupHelper.getFullyQualifiedClass(srcProtoFileContext, fileType, message);
    }

    /**
     * Get the set of fields that are comparable for a given message.
     * @param message The message to get comparable fields for
     * @return set of field names that are comparable
     */
    public List<String> getComparableFields(final MessageDefContext message) {
        return lookupHelper.getComparableFields(message);
    }

    /**
     * Get the Java package a class should be generated into for a given msgDef and file type.
     *
     * @param fileType The type of file we want the package for
     * @param message The msgDef to get package for
     * @return java package to put model class in
     */
    public String getPackageForMessage(FileType fileType, MessageDefContext message) {
        return lookupHelper.getPackage(srcProtoFileContext, fileType, message);
    }

    /**
     * Get the Java package a class should be generated into for a given enum and file type.
     *
     * @param fileType The type of file we want the package for
     * @param enumDef The enum to get package for
     * @return java package to put model class in
     */
    public String getPackageForEnum(FileType fileType, EnumDefContext enumDef) {
        return lookupHelper.getPackage(srcProtoFileContext, fileType, enumDef);
    }

    /**
     * Get the Java package a class should be generated into for a given fieldContext and file type.
     *
     * @param fileType The type of file we want the package for
     * @param fieldContext The field to get package for message type for
     * @return java package to put model class in
     */
    public String getPackageFieldMessageType(final FileType fileType, final FieldContext fieldContext) {
        return lookupHelper.getPackage(srcProtoFileContext, fileType, fieldContext.type_().messageType());
    }

    /**
     * Get the Java package a class should be generated into for a given typeContext and file type.
     *
     * @param fileType The type of file we want the package for
     * @param typeContext The field to get package for message type for
     * @return java package to put model class in
     */
    public String getPackageFieldMessageType(final FileType fileType, final Type_Context typeContext) {
        return lookupHelper.getPackage(srcProtoFileContext, fileType, typeContext.messageType());
    }

    /**
     * Get the PBJ Java package a class should be generated into for a given fieldContext and file type.
     *
     * @param fileType The type of file we want the package for
     * @param fieldContext The field to get package for message type for
     * @return java package to put model class in
     */
    public String getPackageOneofFieldMessageType(final FileType fileType, final OneofFieldContext fieldContext) {
        return lookupHelper.getPackage(srcProtoFileContext, fileType, fieldContext.type_().messageType());
    }

    /**
     * Check if the given messageType is a known enum
     *
     * @param messageType to check if enum
     * @return true if known as an enum, recorded by addEnum()
     */
    public boolean isEnum(MessageTypeContext messageType) {
        return lookupHelper.isEnum(srcProtoFileContext, messageType);
    }
}
