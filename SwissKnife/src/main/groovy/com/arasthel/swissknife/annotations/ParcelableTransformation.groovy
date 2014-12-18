package com.arasthel.swissknife.annotations

import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.util.SparseArray
import android.view.View
import android.widget.TextView
import com.arasthel.swissknife.utils.AnnotationUtils
import groovyjarjarasm.asm.Opcodes
import org.codehaus.groovy.ast.*
import org.codehaus.groovy.ast.builder.AstBuilder
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ArrayExpression
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.CastExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.FieldExpression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.ReturnStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.codehaus.groovy.ast.tools.GeneralUtils
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.syntax.Token
import org.codehaus.groovy.syntax.Types
import org.codehaus.groovy.transform.ASTTransformation
import org.codehaus.groovy.transform.GroovyASTTransformation

@GroovyASTTransformation(phase = CompilePhase.CANONICALIZATION)
public class ParcelableTransformation implements ASTTransformation, Opcodes {

    // Classes that can be written in Parcel
    private static final List<Class> PARCELABLE_CLASSES = [
            String, String[], List, Map, SparseArray, android.os.Parcelable, android.os.Parcelable[], Bundle, CharSequence, Serializable
    ]

    // Classes which need a ClassLoader as an argument for reading
    private static final List<Class> NEED_CLASSLOADER = [
            Bundle, List, Map, android.os.Parcelable, SparseArray
    ]

    def excludedFields = []

    @Override
    void visit(ASTNode[] astNodes, SourceUnit sourceUnit) {
        AnnotationNode annotation = astNodes[0];
        ClassNode annotatedClass = astNodes[1];

        readExcludedFields(annotation, annotatedClass)

        // We implement the interface
        annotatedClass.addInterface(ClassHelper.make(android.os.Parcelable))

        // We add the describeContents method
        MethodNode describeContentsMethod = createDescribeContentsMethod()

        annotatedClass.addMethod(describeContentsMethod)

        // We add the writeToPacel method
        MethodNode writeToParcelMethod = createWriteToParcelMethod(annotatedClass)

        annotatedClass.addMethod(writeToParcelMethod)

        // We add the CREATOR field
        createCREATORField(annotatedClass, sourceUnit)

        // We add an empty constructor
        annotatedClass.addConstructor(createEmptyConstructor());

        // We add a constructor which takes only one Parcel argument
        annotatedClass.addConstructor(createParcelConstructor(annotatedClass));

        println "Generated class: $annotatedClass"

    }

    def readExcludedFields(AnnotationNode annotationNode, ClassNode annotatedClass) {
        Expression excludesExpression = annotationNode.members.exclude as ClosureExpression
        if(excludesExpression) {
            (excludesExpression.getCode() as BlockStatement).getStatements().each {
                String fieldName = ((it as ExpressionStatement).expression as VariableExpression).accessedVariable.name
                FieldNode excluded = annotatedClass.getField(fieldName)
                if(excluded) {
                    excludedFields << excluded
                }
            }
        }
    }

    List<FieldNode> getParcelableFields(ClassNode declaringClass) {
        def parcelableFields = []
        declaringClass.getFields().each { FieldNode field ->
            if(!(field in excludedFields)) {
                // We don't want to parcel static fields
                if (!field.isStatic()) {
                    ClassNode fieldClass = field.getType()
                    // If it's a primitive, it can be parceled
                    if (ClassHelper.isPrimitiveType(fieldClass)) {
                        parcelableFields << field
                    } else if (fieldClass.isArray()) {
                        // If it's an array of primitives, too
                        if (ClassHelper.isPrimitiveType(fieldClass.getComponentType())) {
                            parcelableFields << field
                        } else {
                            // If it's an array of objects, find if it's one of the parcelable classes
                            PARCELABLE_CLASSES.find {
                                if (fieldClass.isDerivedFrom(ClassHelper.make(it))
                                        || fieldClass.implementsInterface(ClassHelper.make(it))) {
                                    parcelableFields << field
                                    return true
                                }
                                return false
                            }
                        }
                    } else {
                        println field.name
                        // If it's an object, check if it's parcelable
                        PARCELABLE_CLASSES.find {
                            if (fieldClass.isDerivedFrom(ClassHelper.make(it))
                                    || fieldClass.implementsInterface(ClassHelper.make(it))) {
                                parcelableFields << field
                                return true
                            }
                            return false
                        }
                    }

                }
            }
        }

        return parcelableFields
    }

    ConstructorNode createParcelConstructor(ClassNode annotatedClass) {
        Statement code = readFromParcelCode(annotatedClass)
        return new ConstructorNode(ACC_PUBLIC, [new Parameter(ClassHelper.make(Parcel), "parcel")] as Parameter[], ClassNode.EMPTY_ARRAY, code)
    }

    ConstructorNode createEmptyConstructor() {
        return new ConstructorNode(ACC_PUBLIC, new BlockStatement())
    }

    MethodNode createDescribeContentsMethod() {
        ReturnStatement returnStatement = new ReturnStatement(new ConstantExpression(0))
        MethodNode methodNode = new MethodNode("describeContents", ACC_PUBLIC, ClassHelper.int_TYPE, [] as Parameter[], [] as ClassNode[], returnStatement)
        return methodNode
    }

    MethodNode createWriteToParcelMethod(ClassNode annotatedClass) {
        Statement statement = writeToParcelCode(annotatedClass)
        Parameter[] parameters = [new Parameter(ClassHelper.make(Parcel), "dest"), new Parameter(ClassHelper.int_TYPE, "flags")]
        MethodNode methodNode = new MethodNode("writeToParcel", ACC_PUBLIC, ClassHelper.VOID_TYPE, parameters, [] as ClassNode[], statement)
        return methodNode
    }

    Statement writeToParcelCode(ClassNode annotatedClass) {
        BlockStatement statement = new BlockStatement()
        List<FieldNode> fields = getParcelableFields(annotatedClass)
        fields.each {
            // Every method will start with "write____" where ___ will be methodPostfix
            String methodPostfix = null
            if (ClassHelper.isPrimitiveType(it.getType())) {
                // Example: int -> writeInt, char -> writeChar
                methodPostfix = it.class.name.capitalize()
            } else {
                if (!it.getType().isArray()) {
                    // If a parcelable object, writeClassName
                    methodPostfix = getImplementedClassNode(it.getType())
                } else {
                    // If an array of parcelables, writeClassNameArray
                    methodPostfix = "${getImplementedClassNode(it.getType().getComponentType())}Array"
                }
            }

            // Every write____ takes the value to write as an argument
            ArgumentListExpression argumentListExpression = new ArgumentListExpression(
                    new VariableExpression("${it.name}", it.getType())
            )

            ClassNode fieldClassNode = it.getType().isArray() ? it.getType().getComponentType() : it.getType()

            // But Parcelable and Parcelable[] also need a "flags" int argument
            if (fieldClassNode.implementsInterface(ClassHelper.make(android.os.Parcelable))) {
                argumentListExpression.addExpression(new VariableExpression("flags"))
            }

            statement.addStatement(
                    new ExpressionStatement(
                            new MethodCallExpression(
                                    new VariableExpression("parcel", ClassHelper.make(Parcel)),
                                    "write$methodPostfix",
                                    argumentListExpression
                            )
                    ))

        }

        return statement
    }

    Statement readFromParcelCode(ClassNode annotatedClass) {
        BlockStatement blockStatement = new BlockStatement()
        List<FieldNode> fields = getParcelableFields(annotatedClass)
        // We create the classLoader variable just in case for complex classes
        blockStatement.addStatement(new ExpressionStatement(
                new BinaryExpression(
                    new VariableExpression("classLoader", ClassHelper.make(ClassLoader)),
                    Token.newSymbol(Types.EQUAL, 0, 0),
                    new MethodCallExpression(new ClassExpression(ClassHelper.make(Object)), "getClassLoader", new ArgumentListExpression())
                )
        ))

        fields.each { FieldNode field ->
            // Every method will be read____
            String methodPostfix = null
            if (ClassHelper.isPrimitiveType(field.getType())) {
                // char -> readChar()
                methodPostfix = field.getType().nameWithoutPackage.capitalize()
            } else {
                if (!field.getType().isArray()) {
                    // If a parcelable object -> readClassName

                    methodPostfix = getImplementedClassNode(field.getType())
                } else {
                    // If an array of parcelable objects -> readClassNameArray

                    methodPostfix = "${getImplementedClassNode(field.getType().getComponentType())}Array"
                }
            }

            ClassNode fieldClass = field.getType().isArray() ? field.getType().getComponentType() : field.getType()
            ArgumentListExpression argumentListExpression = new ArgumentListExpression()

            // For arrays and lists, read____ returns void and field must be passed as an argument
            if (field.getType().isArray() || field.getType().isDerivedFrom(ClassHelper.make(AbstractList))) {
                argumentListExpression.addExpression(new FieldExpression(field))
                // There are some classes that also need the classLoader variable as an argument
                NEED_CLASSLOADER.find {
                    if (fieldClass.isDerivedFrom(ClassHelper.make(it)) || fieldClass.implementsInterface(ClassHelper.make(it))) {
                        argumentListExpression.addExpression(new VariableExpression("classLoader"))
                        return true
                    }
                    return false
                }
                blockStatement.addStatement(
                        new ExpressionStatement(
                                new MethodCallExpression(
                                        new VariableExpression("parcel", ClassHelper.make(Parcel)),
                                        "read$methodPostfix",
                                        argumentListExpression
                                )
                        )
                )
            } else {
                // Else, just "field = parcel.read___()"
                // There are some classes that also need the classLoader variable as an argument
                NEED_CLASSLOADER.find {
                    if (fieldClass.isDerivedFrom(ClassHelper.make(it)) || fieldClass.implementsInterface(ClassHelper.make(it))) {
                        argumentListExpression.addExpression(new VariableExpression("classLoader", ClassHelper.make(ClassLoader)))
                        return true
                    }
                    return false
                }


                blockStatement.addStatement(
                        new ExpressionStatement(
                                new BinaryExpression(
                                        new FieldExpression(field),
                                        Token.newSymbol(Types.EQUAL, 0, 0),
                                        new MethodCallExpression(
                                                new VariableExpression("parcel", ClassHelper.make(Parcel)),
                                                "read$methodPostfix",
                                                argumentListExpression
                                        )
                                )
                        )
                )

            }

            println "read$methodPostfix ${argumentListExpression.expressions.size()}"

        }

        return blockStatement
    }

    String getImplementedClassNode(ClassNode type) {
        String implementedClassName = null
        if(ClassHelper.isPrimitiveType(type)) {
            implementedClassName = type.getNameWithoutPackage().capitalize()
        }
        PARCELABLE_CLASSES.find {
            ClassNode pClassNode = ClassHelper.make(it)
            if(type.isDerivedFrom(pClassNode) || type.implementsInterface(pClassNode)) {
                implementedClassName = pClassNode.nameWithoutPackage
                return true
            }
            return false
        }
        return implementedClassName
    }

    FieldNode createCREATORField(ClassNode ownerClass, SourceUnit sourceUnit) {
        // We take a Creator<MyClass>
        ClassNode creatorInterfaceClassNode = ClassHelper.make(android.os.Parcelable.Creator)
        creatorInterfaceClassNode.genericsTypes = [new GenericsType(ownerClass)]

        // Create an inner class that implements that Creator<MyClass>
        InnerClassNode customCreatorClassNode = new InnerClassNode(ownerClass, "${ownerClass.name}.Creator", ACC_PUBLIC | ACC_STATIC, ClassHelper.OBJECT_TYPE)
        customCreatorClassNode.addInterface(creatorInterfaceClassNode.getPlainNodeReference())

        // Add createFromParcel method to inner class
        customCreatorClassNode.addMethod(createFromParcelMethod(ownerClass, customCreatorClassNode))

        // Add newArray method to inner class
        customCreatorClassNode.addMethod(newArrayMethod(ownerClass, customCreatorClassNode))

        // public static CREATOR = new MyClass.Creator()
        FieldNode creatorField = new FieldNode("CREATOR",
                ACC_PUBLIC | ACC_STATIC,
                creatorInterfaceClassNode.getPlainNodeReference(),
                ownerClass,
                new ConstructorCallExpression(customCreatorClassNode, new ArgumentListExpression()))

        ownerClass.addField(creatorField)

        // This line is needed to add the inner class to the original class
        ownerClass.module.addClass(customCreatorClassNode)

    }

    MethodNode createFromParcelMethod(ClassNode outerClass, InnerClassNode creatorClassNode) {
        // Just call new MyClass(parcel) and return it
        ReturnStatement cfpCode =
                new ReturnStatement(
                        new ExpressionStatement(
                                new ConstructorCallExpression(outerClass.getPlainNodeReference(), new ArgumentListExpression(new VariableExpression("source", ClassHelper.make(Parcel))))
                        )
                )



        MethodNode createFromParcelMethod = new MethodNode("createFromParcel",
                ACC_PUBLIC,
                outerClass.getPlainNodeReference(),
                [new Parameter(ClassHelper.make(Parcel), "source")] as Parameter[],
                [] as ClassNode[],
                cfpCode)

        return createFromParcelMethod
    }

    MethodNode newArrayMethod(ClassNode outerClass, InnerClassNode creatorClassNode) {
        // Return an array of MyClass of the given size
        ClassNode arrayNode = ClassHelper.OBJECT_TYPE.makeArray()

        ExpressionStatement returnExpression = new ExpressionStatement(
                new CastExpression(arrayNode, new ArrayExpression(outerClass, null, Arrays.asList(new VariableExpression("size", ClassHelper.int_TYPE)) as List<Expression>))
        )
        ReturnStatement naCode =
                new ReturnStatement(
                        returnExpression
                )

        MethodNode createNewArrayNode = new MethodNode("newArray",
                ACC_PUBLIC,
                arrayNode,
                [new Parameter(ClassHelper.int_TYPE, "size")] as Parameter[],
                [] as ClassNode[],
                naCode)

        return createNewArrayNode
    }
}