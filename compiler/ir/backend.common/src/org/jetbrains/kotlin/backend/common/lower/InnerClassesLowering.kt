/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.common.lower

import org.jetbrains.kotlin.backend.common.BackendContext
import org.jetbrains.kotlin.backend.common.BodyLoweringPass
import org.jetbrains.kotlin.backend.common.ClassLoweringPass
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.dump
import org.jetbrains.kotlin.ir.util.transformFlat
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import java.util.*

class InnerClassesLowering(val context: BackendContext) : ClassLoweringPass {
    override fun lower(irClass: IrClass) {
        InnerClassTransformer(irClass).lowerInnerClass()
    }

    private inner class InnerClassTransformer(val irClass: IrClass) {
        lateinit var outerThisField: IrField

        val oldConstructorParameterToNew = HashMap<IrValueParameter, IrValueParameter>()
        val class2Symbol = HashMap<IrClass, IrClass>()

        fun lowerInnerClass() {
            if (!irClass.isInner) return
//            rememberClassSymbols()

            createOuterThisField()
            lowerConstructors()
            lowerConstructorParameterUsages()
            lowerOuterThisReferences()
        }
//
//        //TODO: rewrite: this methods is required to 'getClassForImplicitThis' method
//        private fun rememberClassSymbols() {
//            var current = irClass.parent as? IrClass
//            while (current != null) {
//                class2Symbol[current] = current
//                current = current.parent as? IrClass
//            }
//            irClass.acceptVoid(object : IrElementVisitorVoid {
//                override fun visitElement(element: IrElement) {
//                    element.acceptChildrenVoid(this)
//                }
//
//                override fun visitClass(declaration: IrClass) {
//                    return super.visitClass(declaration).also { class2Symbol[declaration] = declaration }
//                }
//            })
//        }

        private fun createOuterThisField() {
            val field = context.descriptorsFactory.getOuterThisFieldSymbol(irClass)
            outerThisField = field.backingField!!
            irClass.declarations += field
//            irClass.declarations.add(
//                IrFieldImpl(
//                    irClass.startOffset, irClass.endOffset,
//                    FIELD_FOR_OUTER_THIS,
//                    fieldSymbol,
//                    irClass.defaultType
//                    ).also {
//                    it.parent = irClass
//                    outerThisField = it
//                }
//            )
        }

        private fun lowerConstructors() {
            irClass.declarations.transformFlat { irMember ->
                if (irMember is IrConstructor)
                    listOf(lowerConstructor(irMember))
                else
                    null
            }
        }

        private fun lowerConstructor(irConstructor: IrConstructor): IrConstructor {
            val startOffset = irConstructor.startOffset
            val endOffset = irConstructor.endOffset

            val loweredConstructor = context.descriptorsFactory.getInnerClassConstructorWithOuterThisParameter(irConstructor)
//            val loweredConstructor = IrConstructorImpl(
//                startOffset, endOffset,
//                irConstructor.origin, // TODO special origin for lowered inner class constructors?
//                newSymbol,
//                null
//            ).apply {
//                returnType = irConstructor.returnType
//            }

//            loweredConstructor.createParameterDeclarations()
            val outerThisValueParameter = loweredConstructor.valueParameters[0].symbol

            irConstructor.valueParameters.forEach { old ->
                oldConstructorParameterToNew[old] = loweredConstructor.valueParameters[old.index + 1]
            }

//            irConstructor.descriptor.valueParameters.forEach { oldValueParameter ->
//                oldConstructorParameterToNew[oldValueParameter] = loweredConstructor.valueParameters[oldValueParameter.index + 1]
//            }

            val blockBody = irConstructor.body as? IrBlockBody ?: throw AssertionError("Unexpected constructor body: ${irConstructor.body}")

            val instanceInitializerIndex = blockBody.statements.indexOfFirst { it is IrInstanceInitializerCall }
            if (instanceInitializerIndex >= 0) {
                // Initializing constructor: initialize 'this.this$0' with '$outer'
                blockBody.statements.add(
                    instanceInitializerIndex,
                    IrSetFieldImpl(
                        startOffset, endOffset, outerThisField.symbol,
                        IrGetValueImpl(startOffset, endOffset, irClass.thisReceiver!!.symbol),
                        IrGetValueImpl(startOffset, endOffset, outerThisValueParameter),
                        context.irBuiltIns.unitType
                    )
                )
            } else {
                // Delegating constructor: invoke old constructor with dispatch receiver '$outer'
                val delegatingConstructorCall = (blockBody.statements.find { it is IrDelegatingConstructorCall }
                        ?: throw AssertionError("Delegating constructor call expected: ${irConstructor.dump()}")
                        ) as IrDelegatingConstructorCall
                delegatingConstructorCall.dispatchReceiver = IrGetValueImpl(
                    delegatingConstructorCall.startOffset, delegatingConstructorCall.endOffset, outerThisValueParameter
                )
            }

            loweredConstructor.body = blockBody
//            loweredConstructor.parent = irConstructor.parent
            return loweredConstructor
        }

        private fun lowerConstructorParameterUsages() {
            irClass.transformChildrenVoid(VariableRemapper(oldConstructorParameterToNew))
        }

        private fun lowerOuterThisReferences() {
            irClass.transformChildrenVoid(object : IrElementTransformerVoid() {


                override fun visitClass(declaration: IrClass): IrStatement =
                //TODO: maybe add another transformer that skips specified elements
                    declaration

                override fun visitGetValue(expression: IrGetValue): IrExpression {
                    expression.transformChildrenVoid(this)

                    val implicitThisClass = expression.symbol.getClassForImplicitThis() ?: return expression

                    if (implicitThisClass == irClass) return expression

                    val startOffset = expression.startOffset
                    val endOffset = expression.endOffset
                    val origin = expression.origin

                    var irThis: IrExpression = IrGetValueImpl(startOffset, endOffset, irClass.thisReceiver!!.symbol, origin)
                    var innerClass = irClass

                    while (innerClass != implicitThisClass) {
                        if (!innerClass.isInner) {
                            // Captured 'this' unrelated to inner classes nesting hierarchy, leave it as is -
                            // should be transformed by closures conversion.
                            return expression
                        }

                        val outerThisField = context.descriptorsFactory.getOuterThisFieldSymbol(innerClass)
                        irThis = IrGetFieldImpl(startOffset, endOffset, outerThisField.backingField!!.symbol, innerClass.defaultType, irThis, origin)

                        val outer = innerClass.parent
                        innerClass = outer as? IrClass ?:
                                throw AssertionError("Unexpected containing declaration for inner class $innerClass: $outer")
                    }

                    return irThis
                }
            })
        }

        private fun IrValueSymbol.getClassForImplicitThis(): IrClass? {


            return owner.parent as? IrClass

//            if (this is IrValueParameterSymbol) {
//                val declaration = owner
//                if (declaration.index == -1) {
//                    if (declaration.name.isSpecial) {
//                        return declaration.parent as IrClass
//                    }
//                }
//            }
//            val descriptor1 = this.descriptor
//            if (descriptor1 is ReceiverParameterDescriptor) {
//                val receiverValue = descriptor1.value
//                if (receiverValue is ImplicitClassReceiver) {
//                    return class2Symbol[receiverValue.classDescriptor]
//                }
//            }
            return null
        }
    }
}

class InnerClassConstructorCallsLowering(val context: BackendContext) : BodyLoweringPass {
    override fun lower(irBody: IrBody) {
        irBody.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitCall(expression: IrCall): IrExpression {
                expression.transformChildrenVoid(this)

                val dispatchReceiver = expression.dispatchReceiver ?: return expression
                val callee = expression.symbol as? IrConstructorSymbol ?: return expression
                val parent = callee.owner.parent as? IrClass ?: return expression
                if (!parent.isInner) return expression

                val newCallee = context.descriptorsFactory.getInnerClassConstructorWithOuterThisParameter(callee.owner)
                val newCall = IrCallImpl(
                    expression.startOffset, expression.endOffset, expression.type, newCallee.symbol, newCallee.descriptor,
                    0, // TODO type arguments map
                    expression.origin
                )

                newCall.putValueArgument(0, dispatchReceiver)
                for (i in 1..newCallee.descriptor.valueParameters.lastIndex) {
                    newCall.putValueArgument(i, expression.getValueArgument(i - 1))
                }

                return newCall
            }

            override fun visitDelegatingConstructorCall(expression: IrDelegatingConstructorCall): IrExpression {
                expression.transformChildrenVoid(this)

                val dispatchReceiver = expression.dispatchReceiver ?: return expression
                val classConstructor = expression.symbol.owner
                if (!(classConstructor.parent as IrClass).isInner) return expression

                val newCallee = context.descriptorsFactory.getInnerClassConstructorWithOuterThisParameter(classConstructor)
                val newCall = IrDelegatingConstructorCallImpl(
                    expression.startOffset, expression.endOffset, context.irBuiltIns.unitType, newCallee.symbol, newCallee.descriptor,
                    classConstructor.typeParameters.size
                ).apply { copyTypeArgumentsFrom(expression) }

                newCall.putValueArgument(0, dispatchReceiver)
                for (i in 1..newCallee.valueParameters.lastIndex) {
                    newCall.putValueArgument(i, expression.getValueArgument(i - 1))
                }

                return newCall
            }

            // TODO callable references?
        })
    }
}

