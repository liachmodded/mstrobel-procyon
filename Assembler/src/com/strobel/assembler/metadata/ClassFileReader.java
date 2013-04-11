/*
 * ClassFileReader.java
 *
 * Copyright (c) 2013 Mike Strobel
 *
 * This source code is based on Mono.Cecil from Jb Evain, Copyright (c) Jb Evain;
 * and ILSpy/ICSharpCode from SharpDevelop, Copyright (c) AlphaSierraPapa.
 *
 * This source code is subject to terms and conditions of the Apache License, Version 2.0.
 * A copy of the license can be found in the License.html file at the root of this distribution.
 * By using this source code in any fashion, you are agreeing to be bound by the terms of the
 * Apache License, Version 2.0.
 *
 * You must not remove this notice, or any other, from this software.
 */

package com.strobel.assembler.metadata;

import com.strobel.assembler.ir.*;
import com.strobel.assembler.ir.attributes.*;
import com.strobel.assembler.metadata.annotations.CustomAnnotation;
import com.strobel.assembler.metadata.annotations.InnerClassEntry;
import com.strobel.assembler.metadata.annotations.InnerClassesAttribute;
import com.strobel.core.ArrayUtilities;
import com.strobel.core.Comparer;
import com.strobel.core.StringUtilities;
import com.strobel.core.VerifyArgument;
import com.strobel.util.EmptyArrayCache;

import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Mike Strobel
 */
@SuppressWarnings("ConstantConditions")
public final class ClassFileReader extends MetadataReader implements ClassReader {
    public final static int OPTION_PROCESS_ANNOTATIONS = 1 << 0;
    public final static int OPTION_PROCESS_CODE = 1 << 1;

    public final static int OPTIONS_DEFAULT = OPTION_PROCESS_ANNOTATIONS;

    final static long MAGIC = 0xCAFEBABEL;

    final int options;
    final IMetadataResolver resolver;
    final long magic;
    final int majorVersion;
    final int minorVersion;
    final Buffer buffer;
    final ConstantPool constantPool;
    final ConstantPool.TypeInfoEntry thisClassEntry;
    final ConstantPool.TypeInfoEntry baseClassEntry;
    final ConstantPool.TypeInfoEntry[] interfaceEntries;
    final List<FieldInfo> fields;
    final List<MethodInfo> methods;
    final List<SourceAttribute> attributes;
    final String name;
    final String packageName;
    final String internalName;

    private final AtomicBoolean _completed;
    private final Scope _scope;

    long flags;

    private ClassFileReader(
        final int options,
        final IMetadataResolver resolver,
        final long magic,
        final int majorVersion,
        final int minorVersion,
        final Buffer buffer,
        final ConstantPool constantPool,
        final int accessFlags,
        final ConstantPool.TypeInfoEntry thisClassEntry,
        final ConstantPool.TypeInfoEntry baseClassEntry,
        final ConstantPool.TypeInfoEntry[] interfaceEntries) {

        super();

        this.options = options;
        this.resolver = resolver;

        _scope = new Scope(this.resolver);

        this.internalName = thisClassEntry.getName();
        this.magic = magic;
        this.majorVersion = majorVersion;
        this.minorVersion = minorVersion;
        this.buffer = buffer;
        this.constantPool = constantPool;
        this.flags = accessFlags;
        this.thisClassEntry = VerifyArgument.notNull(thisClassEntry, "thisClassEntry");
        this.baseClassEntry = baseClassEntry;
        this.interfaceEntries = VerifyArgument.notNull(interfaceEntries, "interfaceEntries");
        this.attributes = new ArrayList<>();
        this.fields = new ArrayList<>();
        this.methods = new ArrayList<>();

        final int delimiter = this.internalName.lastIndexOf('/');

        if (delimiter < 0) {
            this.packageName = StringUtilities.EMPTY;
            this.name = this.internalName;
        }
        else {
            this.packageName = this.internalName.substring(0, delimiter).replace('/', '.');
            this.name = this.internalName.substring(delimiter + 1);
        }

        _completed = new AtomicBoolean();
    }

    protected boolean shouldProcessAnnotations() {
        return (options & OPTION_PROCESS_ANNOTATIONS) == OPTION_PROCESS_ANNOTATIONS;
    }

    protected boolean shouldProcessCode() {
        return (options & OPTION_PROCESS_CODE) == OPTION_PROCESS_CODE;
    }

    @Override
    protected IMetadataScope getScope() {
        return _scope;
    }

    @Override
    public MetadataParser getParser() {
        return _scope._parser;
    }

    @Override
    protected SourceAttribute readAttributeCore(final String name, final Buffer buffer, final int length) {
        VerifyArgument.notNull(name, "name");
        VerifyArgument.notNull(buffer, "buffer");
        VerifyArgument.isNonNegative(length, "length");

        switch (name) {
            case AttributeNames.Code: {
                final int maxStack = buffer.readUnsignedShort();
                final int maxLocals = buffer.readUnsignedShort();
                final int codeLength = buffer.readInt();
                final int codeOffset = buffer.position();
                final byte[] code = new byte[codeLength];

                buffer.read(code, 0, codeLength);

                final int exceptionTableLength = buffer.readUnsignedShort();
                final ExceptionTableEntry[] exceptionTable = new ExceptionTableEntry[exceptionTableLength];

                for (int k = 0; k < exceptionTableLength; k++) {
                    final int startOffset = buffer.readUnsignedShort();
                    final int endOffset = buffer.readUnsignedShort();
                    final int handlerOffset = buffer.readUnsignedShort();
                    final int catchTypeToken = buffer.readUnsignedShort();
                    final TypeReference catchType;

                    if (catchTypeToken == 0) {
                        catchType = null;
                    }
                    else {
                        catchType = _scope.lookupType(catchTypeToken);
                    }

                    exceptionTable[k] = new ExceptionTableEntry(
                        startOffset,
                        endOffset,
                        handlerOffset,
                        catchType
                    );
                }

                final int attributeCount = buffer.readUnsignedShort();
                final SourceAttribute[] attributes = new SourceAttribute[attributeCount];

                readAttributes(buffer, attributes);

                if (shouldProcessCode()) {
                    return new CodeAttribute(
                        length,
                        maxStack,
                        maxLocals,
                        codeOffset,
                        codeLength,
                        buffer,
                        exceptionTable,
                        attributes
                    );
                }
                else {
                    return new CodeAttribute(
                        length,
                        codeLength,
                        maxStack,
                        maxLocals,
                        attributes
                    );
                }
            }

            case AttributeNames.InnerClasses: {
                final InnerClassEntry[] entries = new InnerClassEntry[buffer.readUnsignedShort()];

                for (int i = 0; i < entries.length; i++) {
                    final int innerClassIndex = buffer.readUnsignedShort();
                    final int outerClassIndex = buffer.readUnsignedShort();
                    final int shortNameIndex = buffer.readUnsignedShort();
                    final int accessFlags = buffer.readUnsignedShort();

                    final ConstantPool.TypeInfoEntry innerClass = constantPool.getEntry(innerClassIndex);
                    final ConstantPool.TypeInfoEntry outerClass;

                    if (outerClassIndex != 0) {
                        outerClass = constantPool.getEntry(outerClassIndex);
                    }
                    else {
                        outerClass = null;
                    }

                    entries[i] = new InnerClassEntry(
                        innerClass.getName(),
                        outerClass != null ? outerClass.getName() : null,
                        shortNameIndex != 0 ? constantPool.<String>lookupConstant(shortNameIndex) : null,
                        accessFlags
                    );
                }

                return new InnerClassesAttribute(length, ArrayUtilities.asUnmodifiableList(entries));
            }
        }

        return super.readAttributeCore(name, buffer, length);
    }

    @SuppressWarnings("ConstantConditions")
    private void readAttributesPhaseOne(final Buffer buffer, final SourceAttribute[] attributes) {
        for (int i = 0; i < attributes.length; i++) {
            final int nameIndex = buffer.readUnsignedShort();
            final int length = buffer.readInt();
            final IMetadataScope scope = getScope();
            final String name = scope.lookupConstant(nameIndex);

            switch (name) {
                case AttributeNames.SourceFile: {
                    final int token = buffer.readUnsignedShort();
                    final String sourceFile = scope.lookupConstant(token);
                    attributes[i] = new SourceFileAttribute(sourceFile);
                    continue;
                }

                case AttributeNames.ConstantValue: {
                    final int token = buffer.readUnsignedShort();
                    final Object constantValue = scope.lookupConstant(token);
                    attributes[i] = new ConstantValueAttribute(constantValue);
                    continue;
                }

                case AttributeNames.LineNumberTable: {
                    final int entryCount = buffer.readUnsignedShort();
                    final LineNumberTableEntry[] entries = new LineNumberTableEntry[entryCount];

                    for (int j = 0; j < entries.length; j++) {
                        entries[j] = new LineNumberTableEntry(
                            buffer.readUnsignedShort(),
                            buffer.readUnsignedShort()
                        );
                    }

                    attributes[i] = new LineNumberTableAttribute(entries);
                    continue;
                }

                case AttributeNames.Signature: {
                    final int token = buffer.readUnsignedShort();
                    final String signature = scope.lookupConstant(token);
                    attributes[i] = new SignatureAttribute(signature);
                    continue;
                }

                case AttributeNames.InnerClasses: {
                    attributes[i] = readAttributeCore(name, buffer, length);
                    continue;
                }

                default: {
                    final byte[] blob = new byte[length];
                    buffer.read(blob, 0, blob.length);
                    attributes[i] = new BlobAttribute(name, blob);
                    continue;
                }
            }
        }
    }

    public static ClassFileReader readClass(final IMetadataResolver resolver, final Buffer b) {
        return readClass(OPTIONS_DEFAULT, resolver, b);
    }

    public static ClassFileReader readClass(final int options, final IMetadataResolver resolver, final Buffer b) {
        final long magic = b.readInt() & 0xFFFFFFFFL;

        if (magic != MAGIC) {
            throw new IllegalStateException("Wrong magic number: " + magic);
        }

        final int minorVersion = b.readUnsignedShort();
        final int majorVersion = b.readUnsignedShort();

        final ConstantPool constantPool = ConstantPool.read(b);

        final int accessFlags = b.readUnsignedShort();

        final ConstantPool.TypeInfoEntry thisClass = (ConstantPool.TypeInfoEntry) constantPool.get(b.readUnsignedShort(), ConstantPool.Tag.TypeInfo);
        final ConstantPool.TypeInfoEntry baseClass;

        final int baseClassToken = b.readUnsignedShort();

        if (baseClassToken == 0) {
            baseClass = null;
        }
        else {
            baseClass = constantPool.getEntry(baseClassToken);
        }

        final ConstantPool.TypeInfoEntry interfaces[] = new ConstantPool.TypeInfoEntry[b.readUnsignedShort()];

        for (int i = 0; i < interfaces.length; i++) {
            interfaces[i] = (ConstantPool.TypeInfoEntry) constantPool.get(b.readUnsignedShort(), ConstantPool.Tag.TypeInfo);
        }

        return new ClassFileReader(
            options,
            resolver,
            magic,
            majorVersion,
            minorVersion,
            b,
            constantPool,
            accessFlags,
            thisClass,
            baseClass,
            interfaces
        );
    }

    // <editor-fold defaultstate="collapsed" desc="ClassReader Implementation">

    @Override
    public void accept(final TypeVisitor visitor) {
        if (!_completed.getAndSet(true)) {
            final int fieldCount = buffer.readUnsignedShort();

            for (int i = 0; i < fieldCount; i++) {
                final int accessFlags = buffer.readUnsignedShort();

                final String name = constantPool.lookupUtf8Constant(buffer.readUnsignedShort());
                final String descriptor = constantPool.lookupUtf8Constant(buffer.readUnsignedShort());

                final SourceAttribute[] attributes;
                final int attributeCount = buffer.readUnsignedShort();

                if (attributeCount > 0) {
                    attributes = new SourceAttribute[attributeCount];
                    readAttributesPhaseOne(buffer, attributes);
                }
                else {
                    attributes = EmptyArrayCache.fromElementType(SourceAttribute.class);
                }

                final FieldInfo field = new FieldInfo(accessFlags, name, descriptor, attributes);

                fields.add(field);
            }

            final int methodCount = buffer.readUnsignedShort();

            for (int i = 0; i < methodCount; i++) {
                final int accessFlags = buffer.readUnsignedShort();

                final String name = constantPool.lookupUtf8Constant(buffer.readUnsignedShort());
                final String descriptor = constantPool.lookupUtf8Constant(buffer.readUnsignedShort());

                final SourceAttribute[] attributes;
                final int attributeCount = buffer.readUnsignedShort();

                if (attributeCount > 0) {
                    attributes = new SourceAttribute[attributeCount];
                    readAttributesPhaseOne(buffer, attributes);
                }
                else {
                    attributes = EmptyArrayCache.fromElementType(SourceAttribute.class);
                }

                final MethodInfo field = new MethodInfo(accessFlags, name, descriptor, attributes);

                methods.add(field);
            }

            final int typeAttributeCount = buffer.readUnsignedShort();

            if (typeAttributeCount > 0) {
                final SourceAttribute[] typeAttributes = new SourceAttribute[typeAttributeCount];

                readAttributesPhaseOne(buffer, typeAttributes);

                for (final SourceAttribute typeAttribute : typeAttributes) {
                    this.attributes.add(typeAttribute);
                }
            }
        }

        SourceAttribute enclosingMethod = SourceAttribute.find(AttributeNames.EnclosingMethod, this.attributes);

        final MethodReference declaringMethod;

        //noinspection UnusedDeclaration
        try (final AutoCloseable ignored = _scope._parser.suppressTypeResolution()) {
            if (enclosingMethod instanceof BlobAttribute) {
                enclosingMethod = inflateAttribute(enclosingMethod);
            }

            if (enclosingMethod instanceof EnclosingMethodAttribute) {
                MethodReference method = ((EnclosingMethodAttribute) enclosingMethod).getEnclosingMethod();

                if (method != null) {
                    final MethodDefinition resolvedMethod = method.resolve();

                    if (resolvedMethod != null) {
                        method = resolvedMethod;
                    }
                }

                declaringMethod = method;
            }
            else {
                declaringMethod = null;
            }
        }
        catch (Exception e) {
            throw new UndeclaredThrowableException(e);
        }

        final IResolverFrame temporaryFrame;

        if (declaringMethod != null &&
            (declaringMethod.containsGenericParameters() || declaringMethod.getDeclaringType().containsGenericParameters())) {

            temporaryFrame = new IResolverFrame() {
                @Override
                public TypeReference findType(final String descriptor) {
                    return null;
                }

                @Override
                public TypeReference findTypeVariable(final String name) {
                    for (final GenericParameter parameter : declaringMethod.getGenericParameters()) {
                        if (parameter.getName().equals(name)) {
                            return parameter;
                        }
                    }

                    TypeReference type = declaringMethod.getDeclaringType();

                    while (type != null) {
                        if (type.hasGenericParameters()) {
                            for (final GenericParameter parameter : type.getGenericParameters()) {
                                if (parameter.getName().equals(name)) {
                                    return parameter;
                                }
                            }
                        }

                        final TypeDefinition resolvedType = type.resolve();

                        if (resolvedType == null) {
                            break;
                        }

                        type = resolvedType.getBaseType();
                    }

                    return null;
                }
            };

            _scope._parser.getResolver().pushFrame(temporaryFrame);
        }
        else {
            temporaryFrame = null;
        }

        try {
            visitor.visitParser(getParser());
            populateDeclaringType(visitor);
            visitHeader(visitor);

            if (declaringMethod != null) {
                visitor.visitDeclaringMethod(declaringMethod);
            }

            populateNamedInnerTypes(visitor);
            visitAttributes(visitor);
            visitConstantPool(visitor);
            visitFields(visitor);
            visitMethods(visitor);
            populateAnonymousInnerTypes(visitor);
            visitor.visitEnd();
        }
        finally {
            if (temporaryFrame != null) {
                _scope._parser.getResolver().popFrame();
            }
        }
    }

    private void visitConstantPool(final TypeVisitor visitor) {
        final ConstantPool.Visitor constantPoolVisitor = visitor.visitConstantPool();

        for (final ConstantPool.Entry entry : constantPool) {
            if (entry == null) {
                continue;
            }
            constantPoolVisitor.visit(entry);
        }

        constantPoolVisitor.visitEnd();
    }

    private void populateDeclaringType(final TypeVisitor visitor) {
        final InnerClassesAttribute innerClasses = SourceAttribute.find(AttributeNames.InnerClasses, this.attributes);

        if (innerClasses == null) {
            return;
        }

        for (final InnerClassEntry entry : innerClasses.getEntries()) {
            final String innerClassName = entry.getInnerClassName();

            String outerClassName = entry.getOuterClassName();

            if (Comparer.equals(innerClassName, this.internalName)) {
                final TypeReference outerType;
                final TypeReference resolvedOuterType;

                if (outerClassName == null) {
                    final int delimiterIndex = innerClassName.lastIndexOf('$');

                    if (delimiterIndex >= 0) {
                        outerClassName = innerClassName.substring(0, delimiterIndex);
                    }
                    else {
                        continue;
                    }
                }

                if (StringUtilities.isNullOrEmpty(entry.getShortName())) {
                    flags |= Flags.ANONYMOUS;
                }

                flags |= entry.getAccessFlags();

                outerType = _scope._parser.parseTypeDescriptor(outerClassName);
                resolvedOuterType = outerType.resolve();

                if (resolvedOuterType != null) {
                    visitor.visitOuterType(outerType);
                }

                return;
            }
        }
    }

    private void visitHeader(final TypeVisitor visitor) {
        final SignatureAttribute signature = SourceAttribute.find(AttributeNames.Signature, attributes);
        final String[] interfaceNames = new String[interfaceEntries.length];

        for (int i = 0; i < interfaceEntries.length; i++) {
            interfaceNames[i] = interfaceEntries[i].getName();
        }

        visitor.visit(
            majorVersion,
            minorVersion,
            flags,
            thisClassEntry.getName(),
            signature != null ? signature.getSignature() : null,
            baseClassEntry != null ? baseClassEntry.getName() : null,
            interfaceNames
        );
    }

    private void populateNamedInnerTypes(final TypeVisitor visitor) {
        final InnerClassesAttribute innerClasses = SourceAttribute.find(AttributeNames.InnerClasses, this.attributes);

        if (innerClasses == null) {
            return;
        }

        for (final InnerClassEntry entry : innerClasses.getEntries()) {
            final String outerClassName = entry.getOuterClassName();

            if (outerClassName == null) {
                continue;
            }

            final String innerClassName = entry.getInnerClassName();

            if (Comparer.equals(this.internalName, innerClassName)) {
                continue;
            }

            final TypeReference innerType = _scope._parser.parseTypeDescriptor(innerClassName);
            final TypeReference resolvedInnerType = innerType.resolve();

            if (resolvedInnerType instanceof TypeDefinition &&
                Comparer.equals(this.internalName, outerClassName)) {

                visitor.visitInnerType((TypeDefinition) resolvedInnerType);
            }
        }
    }

    private void populateAnonymousInnerTypes(final TypeVisitor visitor) {
        final InnerClassesAttribute innerClasses = SourceAttribute.find(AttributeNames.InnerClasses, this.attributes);

        if (innerClasses == null) {
            return;
        }

        for (final InnerClassEntry entry : innerClasses.getEntries()) {
            final String simpleName = entry.getShortName();

            if (!StringUtilities.isNullOrEmpty(simpleName)) {
                continue;
            }

            final String outerClassName = entry.getOuterClassName();
            final String innerClassName = entry.getInnerClassName();

            if (outerClassName == null || Comparer.equals(innerClassName, this.internalName)) {
                continue;
            }

            final TypeReference innerType = _scope._parser.parseTypeDescriptor(innerClassName);
            final TypeReference resolvedInnerType = innerType.resolve();

            if (resolvedInnerType instanceof TypeDefinition &&
                Comparer.equals(this.internalName, outerClassName)) {

                visitor.visitInnerType((TypeDefinition) resolvedInnerType);
            }
        }

        final TypeReference self = _scope._parser.getResolver().lookupType(internalName);

        if (self != null && self.isNested()) {
            return;
        }

        for (final InnerClassEntry entry : innerClasses.getEntries()) {
            final String outerClassName = entry.getOuterClassName();

            if (outerClassName != null) {
                continue;
            }

            final String innerClassName = entry.getInnerClassName();

            if (Comparer.equals(innerClassName, this.internalName)) {
                continue;
            }

            final TypeReference innerType = _scope._parser.parseTypeDescriptor(innerClassName);
            final TypeReference resolvedInnerType = innerType.resolve();

            if (resolvedInnerType instanceof TypeDefinition &&
                Comparer.equals(this.internalName, outerClassName)) {

                visitor.visitInnerType((TypeDefinition) resolvedInnerType);
            }
        }
    }

    @SuppressWarnings("ConstantConditions")
    private void visitFields(final TypeVisitor visitor) {
        for (final FieldInfo field : fields) {
            final TypeReference fieldType;
            final SignatureAttribute signature = SourceAttribute.find(AttributeNames.Signature, field.attributes);

            if (signature != null) {
                fieldType = _scope._parser.parseTypeSignature(signature.getSignature());
            }
            else {
                fieldType = _scope._parser.parseTypeSignature(field.descriptor);
            }

            final FieldVisitor fieldVisitor = visitor.visitField(
                field.accessFlags,
                field.name,
                fieldType
            );

            inflateAttributes(field.attributes);

            for (final SourceAttribute attribute : field.attributes) {
                fieldVisitor.visitAttribute(attribute);
            }

            if (shouldProcessAnnotations()) {
                final AnnotationsAttribute visibleAnnotations = SourceAttribute.find(
                    AttributeNames.RuntimeVisibleAnnotations,
                    field.attributes
                );

                final AnnotationsAttribute invisibleAnnotations = SourceAttribute.find(
                    AttributeNames.RuntimeInvisibleAnnotations,
                    field.attributes
                );

                if (visibleAnnotations != null) {
                    for (final CustomAnnotation annotation : visibleAnnotations.getAnnotations()) {
                        fieldVisitor.visitAnnotation(annotation, true);
                    }
                }

                if (invisibleAnnotations != null) {
                    for (final CustomAnnotation annotation : invisibleAnnotations.getAnnotations()) {
                        fieldVisitor.visitAnnotation(annotation, false);
                    }
                }
            }

            fieldVisitor.visitEnd();
        }
    }

    @SuppressWarnings("ConstantConditions")
    private void visitMethods(final TypeVisitor visitor) {
        //noinspection UnusedDeclaration
        try (final AutoCloseable ignored = _scope._parser.suppressTypeResolution()) {
            for (final MethodInfo method : methods) {
                final IMethodSignature methodSignature;
                final TypeReference[] thrownTypes;

                final SignatureAttribute signature = SourceAttribute.find(AttributeNames.Signature, method.attributes);

                if ((flags & Flags.ENUM) != 0 && "<init>".equals(method.name)) {
                    methodSignature = _scope._parser.parseMethodSignature(
                        signature != null ? "(Ljava/lang/String;I" + signature.getSignature().substring(1)
                                          : method.descriptor
                    );

                    methodSignature.getParameters().get(0).setName("name");
                    methodSignature.getParameters().get(1).setName("ordinal");
                }
                else {
                    methodSignature = _scope._parser.parseMethodSignature(
                        signature != null ? signature.getSignature() : method.descriptor
                    );
                }

                final boolean hasGenericParameters = methodSignature.hasGenericParameters();

                if (hasGenericParameters) {
                    _scope._parser.pushGenericContext(methodSignature);
                }

                try {
                    inflateAttributes(method.attributes);

                    method.codeAttribute = SourceAttribute.find(AttributeNames.Code, method.attributes);

                    final ExceptionsAttribute exceptions = SourceAttribute.find(AttributeNames.Exceptions, method.attributes);

                    if (exceptions != null) {
                        final List<TypeReference> exceptionTypes = exceptions.getExceptionTypes();
                        thrownTypes = exceptionTypes.toArray(new TypeReference[exceptionTypes.size()]);
                    }
                    else {
                        thrownTypes = EmptyArrayCache.fromElementType(TypeReference.class);
                    }

                    long methodFlags = method.accessFlags;

                    if (Flags.testAny(flags, Flags.ANONYMOUS) && "<init>".equals(method.name)) {
                        methodFlags |= Flags.ANONCONSTR | Flags.SYNTHETIC;
                    }

                    final MethodVisitor methodVisitor = visitor.visitMethod(
                        methodFlags,
                        method.name,
                        methodSignature,
                        thrownTypes
                    );

                    if (Flags.testAny(options, OPTION_PROCESS_CODE)) {
                        visitMethodBody(method, methodSignature, methodVisitor);
                    }

                    for (final SourceAttribute attribute : method.attributes) {
                        methodVisitor.visitAttribute(attribute);

                        if (attribute instanceof CodeAttribute) {
                            for (final SourceAttribute bodyAttribute : ((CodeAttribute) attribute).getAttributes()) {
                                methodVisitor.visitAttribute(bodyAttribute);
                            }
                        }
                    }

                    if (shouldProcessAnnotations()) {
                        final AnnotationsAttribute visibleAnnotations = SourceAttribute.find(
                            AttributeNames.RuntimeVisibleAnnotations,
                            method.attributes
                        );

                        final AnnotationsAttribute invisibleAnnotations = SourceAttribute.find(
                            AttributeNames.RuntimeInvisibleAnnotations,
                            method.attributes
                        );

                        if (visibleAnnotations != null) {
                            for (final CustomAnnotation annotation : visibleAnnotations.getAnnotations()) {
                                methodVisitor.visitAnnotation(annotation, true);
                            }
                        }

                        if (invisibleAnnotations != null) {
                            for (final CustomAnnotation annotation : invisibleAnnotations.getAnnotations()) {
                                methodVisitor.visitAnnotation(annotation, false);
                            }
                        }
                    }

                    methodVisitor.visitEnd();
                }
                finally {
                    if (hasGenericParameters) {
                        _scope._parser.popGenericContext();
                    }
                }
            }
        }
        catch (Exception e) {
            throw new UndeclaredThrowableException(e);
        }
    }

    private void visitMethodBody(final MethodInfo methodInfo, final IMethodSignature methodSignature, final MethodVisitor visitor) {
        if (methodInfo.codeAttribute instanceof CodeAttribute) {
            final CodeAttribute codeAttribute = (CodeAttribute) methodInfo.codeAttribute;
            final TypeReference thisType = _scope._parser.lookupType(this.packageName, this.name);

            final MethodReader reader = new MethodReader(
                thisType,
                methodSignature,
                methodInfo.accessFlags,
                codeAttribute,
                _scope
            );

            if (visitor.canVisitBody()) {
                final MethodReference methodReference;
                final MethodBody body = reader.accept(visitor);

                final SignatureAttribute signatureAttribute = SourceAttribute.find(AttributeNames.Signature, methodInfo.attributes);

                if (signatureAttribute != null) {
                    methodReference = _scope._parser.parseMethod(thisType, methodInfo.name, signatureAttribute.getSignature());
                }
                else {
                    methodReference = _scope._parser.parseMethod(thisType, methodInfo.name, methodInfo.descriptor);
                }

                if (methodReference != null) {
                    body.setMethod(methodReference);
                }

                MethodDefinition method;

                if (methodReference != null) {
                    method = methodReference.resolve();
                }
                else {
                    method = null;
                }

                if (method != null) {
                    method.setBody(body);
                }

                body.freeze();

                final InstructionVisitor instructionVisitor = visitor.visitBody(body);
                final InstructionCollection instructions = body.getInstructions();

                final LineNumberTableAttribute lineNumbersAttribute = SourceAttribute.find(
                    AttributeNames.LineNumberTable,
                    codeAttribute.getAttributes()
                );

                final int[] lineNumbers;

                if (lineNumbersAttribute != null) {
                    final List<LineNumberTableEntry> entries = lineNumbersAttribute.getEntries();

                    lineNumbers = new int[instructions.size()];

                    Arrays.fill(lineNumbers, -1);

                    for (int i = 0, j = 0; i < instructions.size() && j < entries.size(); i++) {
                        final Instruction instruction = instructions.get(i);
                        final LineNumberTableEntry entry = entries.get(j);

                        if (entry.getOffset() == instruction.getOffset()) {
                            lineNumbers[i] = entry.getLineNumber();
                            ++j;
                        }
                    }
                }
                else {
                    lineNumbers = null;
                }

                for (int i = 0; i < instructions.size(); i++) {
                    final Instruction inst = instructions.get(i);
                    final int lineNumber = lineNumbers != null ? lineNumbers[i] : -1;

                    if (lineNumber >= 0) {
                        visitor.visitLineNumber(inst, lineNumber);
                    }
                }

                if (instructionVisitor != null) {
                    for (int i = 0; i < instructions.size(); i++) {
                        instructionVisitor.visit(instructions.get(i));
                    }
                    instructionVisitor.visitEnd();
                }

                if (method == null && methodReference != null) {
                    method = methodReference.resolve();

                    if (method != null) {
                        body.setMethod(method);
                    }
                }
            }
        }
    }

    private void visitAttributes(final TypeVisitor visitor) {
        inflateAttributes(this.attributes);

        for (final SourceAttribute attribute : attributes) {
            visitor.visitAttribute(attribute);
        }

        if (shouldProcessAnnotations()) {
            final AnnotationsAttribute visibleAnnotations = SourceAttribute.find(
                AttributeNames.RuntimeVisibleAnnotations,
                this.attributes
            );

            final AnnotationsAttribute invisibleAnnotations = SourceAttribute.find(
                AttributeNames.RuntimeInvisibleAnnotations,
                this.attributes
            );

            if (visibleAnnotations != null) {
                for (final CustomAnnotation annotation : visibleAnnotations.getAnnotations()) {
                    visitor.visitAnnotation(annotation, true);
                }
            }

            if (invisibleAnnotations != null) {
                for (final CustomAnnotation annotation : invisibleAnnotations.getAnnotations()) {
                    visitor.visitAnnotation(annotation, false);
                }
            }
        }
    }

    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="FieldInfo Class">

    final class FieldInfo {
        final int accessFlags;
        final String name;
        final String descriptor;
        final SourceAttribute[] attributes;

        FieldInfo(final int accessFlags, final String name, final String descriptor, final SourceAttribute[] attributes) {
            this.accessFlags = accessFlags;
            this.name = name;
            this.descriptor = descriptor;
            this.attributes = attributes;
        }
    }

    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="MethodInfo Class">

    final class MethodInfo {
        final int accessFlags;
        final String name;
        final String descriptor;
        final SourceAttribute[] attributes;

        SourceAttribute codeAttribute;

        MethodInfo(final int accessFlags, final String name, final String descriptor, final SourceAttribute[] attributes) {
            this.accessFlags = accessFlags;
            this.name = name;
            this.descriptor = descriptor;
            this.attributes = attributes;
            this.codeAttribute = SourceAttribute.find(AttributeNames.Code, attributes);
        }
    }

    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="Metadata Scope">

    private class Scope implements IMetadataScope {
        private final MetadataParser _parser;

        Scope(final IMetadataResolver resolver) {
            _parser = new MetadataParser(VerifyArgument.notNull(resolver, "resolver"));
        }

        @Override
        public TypeReference lookupType(final int token) {
            final ConstantPool.Entry entry = constantPool.get(token);

            if (entry instanceof ConstantPool.TypeInfoEntry) {
                final ConstantPool.TypeInfoEntry typeInfo = (ConstantPool.TypeInfoEntry) entry;

                return _parser.parseTypeDescriptor(typeInfo.getName());
            }

            final String typeName = constantPool.lookupConstant(token);

            return _parser.parseTypeSignature(typeName);
        }

        @Override
        public FieldReference lookupField(final int token) {
            final ConstantPool.FieldReferenceEntry entry = constantPool.getEntry(token);
            return lookupField(entry.typeInfoIndex, entry.nameAndTypeDescriptorIndex);
        }

        @Override
        public MethodReference lookupMethod(final int token) {
            final ConstantPool.Entry entry = constantPool.getEntry(token);
            final ConstantPool.ReferenceEntry reference;

            if (entry instanceof ConstantPool.MethodHandleEntry) {
                final ConstantPool.MethodHandleEntry methodHandle = (ConstantPool.MethodHandleEntry) entry;
                reference = constantPool.getEntry(methodHandle.referenceIndex);
            }
            else {
                reference = (ConstantPool.ReferenceEntry) entry;
            }

            return lookupMethod(reference.typeInfoIndex, reference.nameAndTypeDescriptorIndex);
        }

        @Override
        public IMethodSignature lookupMethodType(final int token) {
            final ConstantPool.MethodTypeEntry entry = constantPool.getEntry(token);
            return _parser.parseMethodSignature(entry.getType());
        }

        @Override
        public DynamicCallSite lookupDynamicCallSite(final int token) {
            final ConstantPool.InvokeDynamicInfoEntry entry = constantPool.getEntry(token);
            final SourceAttribute attribute = SourceAttribute.find(AttributeNames.BootstrapMethods, attributes);
            final BootstrapMethodsAttribute bootstrapMethods;

            if (attribute instanceof BlobAttribute) {
                bootstrapMethods = (BootstrapMethodsAttribute) inflateAttribute(attribute);
            }
            else {
                bootstrapMethods = (BootstrapMethodsAttribute) attribute;
            }

            final BootstrapMethodsTableEntry bootstrapMethod = bootstrapMethods.getBootstrapMethods()
                                                                               .get(entry.bootstrapMethodAttributeIndex);

            final ConstantPool.NameAndTypeDescriptorEntry nameAndType = constantPool.getEntry(entry.nameAndTypeDescriptorIndex);

            return new DynamicCallSite(
                bootstrapMethod.getMethod(),
                bootstrapMethod.getArguments(),
                nameAndType.getName(),
                _parser.parseMethodSignature(nameAndType.getType())
            );
        }

        @Override
        public FieldReference lookupField(final int typeToken, final int nameAndTypeToken) {
            final ConstantPool.NameAndTypeDescriptorEntry nameAndDescriptor = constantPool.getEntry(nameAndTypeToken);

            return _parser.parseField(
                lookupType(typeToken),
                nameAndDescriptor.getName(),
                nameAndDescriptor.getType()
            );
        }

        @Override
        public MethodReference lookupMethod(final int typeToken, final int nameAndTypeToken) {
            final ConstantPool.NameAndTypeDescriptorEntry nameAndDescriptor = constantPool.getEntry(nameAndTypeToken);

            return _parser.parseMethod(
                lookupType(typeToken),
                nameAndDescriptor.getName(),
                nameAndDescriptor.getType()
            );
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T lookupConstant(final int token) {
            final ConstantPool.Entry entry = constantPool.get(token);

            if (entry.getTag() == ConstantPool.Tag.TypeInfo) {
                return (T) lookupType(token);
            }

            return constantPool.lookupConstant(token);
        }
    }

    // </editor-fold>
}