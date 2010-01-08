/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.more.core.classcode;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.more.core.asm.ClassAdapter;
import org.more.core.asm.ClassVisitor;
import org.more.core.asm.FieldVisitor;
import org.more.core.asm.MethodVisitor;
import org.more.core.asm.Opcodes;
/**
 * 完成AOP代理的字节码改写对象。
 * @version 2009-10-30
 * @author 赵永春 (zyc@byshell.org)
 */
class AOPClassAdapter extends ClassAdapter implements Opcodes {
    //========================================================================================Field
    /** 当前类类名 */
    private String                                          thisClassByASM = null;
    private LinkedHashMap<String, java.lang.reflect.Method> classMethods   = new LinkedHashMap<String, java.lang.reflect.Method>(0);
    private ClassEngine                                     engine         = null;
    //==================================================================================Constructor
    public AOPClassAdapter(ClassVisitor cv, ClassEngine engine) {
        super(cv);
        this.engine = engine;
        try {
            LinkedList<java.lang.reflect.Method> listMethod = new LinkedList<java.lang.reflect.Method>();
            for (java.lang.reflect.Method m : engine.getSuperClass().getDeclaredMethods())
                listMethod.add(m);
            for (java.lang.reflect.Method m : engine.getSuperClass().getMethods())
                if (listMethod.contains(m) == false)
                    listMethod.add(m);
            for (Class<?> c : engine.getAppendImpls())
                for (java.lang.reflect.Method m : c.getMethods())
                    if (listMethod.contains(m) == false)
                        listMethod.add(m);
            //
            for (java.lang.reflect.Method m : listMethod) {
                String desc = EngineToos.toAsmType(m.getParameterTypes());
                String returnDesc = EngineToos.toAsmType(m.getReturnType());
                String fullName = m.getName() + "(" + desc + ")" + returnDesc;
                this.classMethods.put(fullName, m);
            }
        } catch (Exception e) {}
    }
    //==========================================================================================Job
    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        this.thisClassByASM = name;
        super.visit(version, access, name, signature, superName, interfaces);
    }
    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        String fullDesc = name + desc;
        //1.忽略内部决定忽略的方法。
        if (this.engine.ignoreMethod(fullDesc) == false)
            return super.visitMethod(access, name, desc, signature, exceptions);
        //2.忽略ClassEngine.acceptMethod决定忽略的方法。
        if (engine.acceptMethod(classMethods.get(fullDesc)) == false)
            return super.visitMethod(access, name, desc, signature, exceptions);
        //3.输出新方法
        this.engine.aopMethods.put(fullDesc, null);
        String newMethodName = ClassEngine.AOPMethodNamePrefix + name;
        MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
        this.visitAOPMethod(mv, name, desc);
        //5.更改名称输出老方法
        return super.visitMethod(ACC_PUBLIC, newMethodName, desc, signature, exceptions);
    }
    @Override
    public void visitEnd() {
        {
            //1.输出代理字段
            FieldVisitor field = super.visitField(ACC_PRIVATE, ClassEngine.AOPFilterChainName, "Lorg/more/core/classcode/ImplAOPFilterChain;", null, null);
            field.visitEnd();
            //2.输出代理字段的注入方法,方法名仅仅是代理字段的名称前面加上set代理字段首字母不需要大写。
            MethodVisitor mv = super.visitMethod(ACC_PUBLIC, "set" + ClassEngine.AOPFilterChainName, "(Lorg/more/core/classcode/ImplAOPFilterChain;)V", null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);//装载this
            mv.visitVarInsn(ALOAD, 1);//装载参数 
            mv.visitFieldInsn(PUTFIELD, this.thisClassByASM, ClassEngine.AOPFilterChainName, "Lorg/more/core/classcode/ImplAOPFilterChain;");
            mv.visitInsn(RETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
        }
        super.visitEnd();
    }
    /** 实现AOP方法 */
    public void visitAOPMethod(final MethodVisitor mv, String name, String desc) {//, final Method method) {
        Pattern p = Pattern.compile("\\((.*)\\)(.*)");
        Matcher m = p.matcher(desc);
        m.find();
        String[] asmParams = EngineToos.splitAsmType(m.group(1));//"IIIILjava/lang/Integer;F[[[ILjava/lang.Boolean;"
        String asmReturns = m.group(2);
        asmReturns = (asmReturns.charAt(0) == 'L') ? asmReturns.substring(1, asmReturns.length() - 1) : asmReturns;
        int paramCount = asmParams.length;
        int localVarSize = paramCount;//方法变量表大小
        int maxStackSize = 0;//方法最大堆栈大小
        //-----------------------------------------------------------------------------------------------------------------------
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, this.thisClassByASM, ClassEngine.AOPFilterChainName, "Lorg/more/core/classcode/ImplAOPFilterChain;");
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn(name + desc);
        mv.visitIntInsn(BIPUSH, paramCount);
        mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        for (int i = 0; i < paramCount; i++) {
            String asmType = asmParams[i];
            mv.visitInsn(DUP);
            mv.visitIntInsn(BIPUSH, i);
            if (asmParams[i].equals("B")) {
                mv.visitVarInsn(EngineToos.getLoad(asmType), i + 1);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;");
            } else if (asmParams[i].equals("S")) {
                mv.visitVarInsn(EngineToos.getLoad(asmType), i + 1);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;");
            } else if (asmParams[i].equals("I")) {
                mv.visitVarInsn(EngineToos.getLoad(asmType), i + 1);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;");
            } else if (asmParams[i].equals("J")) {
                mv.visitVarInsn(EngineToos.getLoad(asmType), i + 1);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;");
            } else if (asmParams[i].equals("F")) {
                mv.visitVarInsn(EngineToos.getLoad(asmType), i + 1);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;");
            } else if (asmParams[i].equals("D")) {
                mv.visitVarInsn(EngineToos.getLoad(asmType), i + 1);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;");
            } else if (asmParams[i].equals("C")) {
                mv.visitVarInsn(EngineToos.getLoad(asmType), i + 1);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;");
            } else if (asmParams[i].equals("Z")) {
                mv.visitVarInsn(EngineToos.getLoad(asmType), i + 1);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;");
            } else
                mv.visitVarInsn(ALOAD, i + 1);
            mv.visitInsn(AASTORE);
            maxStackSize = (maxStackSize < 8 + i) ? 8 + i : maxStackSize;
        }
        String desc2 = "Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;";
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/more/core/classcode/ImplAOPFilterChain", "doInvoke", "(" + desc2 + ")Ljava/lang/Object;");
        mv.visitVarInsn(ASTORE, paramCount + 3);
        localVarSize++;
        //obj = AOPFilterChainName.doInvokeFilter(this,thisMethod, new Object[] { methodCode });---------------------------------
        mv.visitVarInsn(ALOAD, paramCount + 3);
        if (asmReturns.equals("B") == true) {
            mv.visitTypeInsn(CHECKCAST, "java/lang/Byte");
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Byte", "byteValue", "()B");
            mv.visitInsn(EngineToos.getReturn("B"));
        } else if (asmReturns.equals("S") == true) {
            mv.visitTypeInsn(CHECKCAST, "java/lang/Short");
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Short", "shortValue", "()S");
            mv.visitInsn(EngineToos.getReturn("S"));
        } else if (asmReturns.equals("I") == true) {
            mv.visitTypeInsn(CHECKCAST, "java/lang/Integer");
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I");
            mv.visitInsn(EngineToos.getReturn("I"));
        } else if (asmReturns.equals("J") == true) {
            mv.visitTypeInsn(CHECKCAST, "java/lang/Long");
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J");
            mv.visitInsn(EngineToos.getReturn("J"));
        } else if (asmReturns.equals("F") == true) {
            mv.visitTypeInsn(CHECKCAST, "java/lang/Float");
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Float", "floatValue", "()F");
            mv.visitInsn(EngineToos.getReturn("F"));
        } else if (asmReturns.equals("D") == true) {
            mv.visitTypeInsn(CHECKCAST, "java/lang/Double");
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D");
            mv.visitInsn(EngineToos.getReturn("D"));
        } else if (asmReturns.equals("C") == true) {
            mv.visitTypeInsn(CHECKCAST, "java/lang/Character");
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C");
            mv.visitInsn(EngineToos.getReturn("C"));
        } else if (asmReturns.equals("Z") == true) {
            mv.visitTypeInsn(CHECKCAST, "java/lang/Boolean");
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z");
            mv.visitInsn(EngineToos.getReturn("Z"));
        } else if (asmReturns.equals("V") == true) {
            mv.visitInsn(RETURN);
        } else {
            mv.visitTypeInsn(CHECKCAST, asmReturns);
            mv.visitInsn(ARETURN);
        }
        //return obj-------------------------------------------------------------------------------------------------------------
        /* 输出堆栈列表 */
        mv.visitMaxs(maxStackSize, localVarSize + 1);
        mv.visitEnd();
    }
}