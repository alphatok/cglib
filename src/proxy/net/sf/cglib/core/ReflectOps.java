package net.sf.cglib.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.lang.reflect.*;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;

public class ReflectOps {
    private static final Signature GET_DECLARED_METHOD =
      TypeUtils.parseSignature("java.lang.reflect.Method getDeclaredMethod(String, Class[])");
    private static final Signature GET_NAME =
      TypeUtils.parseSignature("String getName()");
    private static final Signature EQUALS =
      TypeUtils.parseSignature("boolean equals(Object)");
    private static final Type BIG_INTEGER =
      TypeUtils.parseType("java.math.BigInteger");
    private static final Type BIG_DECIMAL =
      TypeUtils.parseType("java.math.BigDecimal");
    

    private ReflectOps() {
    }

    public static void load_method(Emitter e, Method method) {
        ComplexOps.load_class(e, Type.getType(method.getDeclaringClass()));
        e.push(method.getName());
        push_object(e, method.getParameterTypes());
        e.invoke_virtual(Constants.TYPE_CLASS, GET_DECLARED_METHOD);
    }

    public static void begin_constructor(Emitter e, Constructor constructor) {
        e.begin_method(Constants.ACC_PUBLIC, // constructor.getModifiers(),
                       ReflectUtils.getSignature(constructor),
                       TypeUtils.getTypes(constructor.getExceptionTypes()));
    }

    public static void begin_method(Emitter e,
                                    int access,
                                    String name,
                                    Class returnType,
                                    Class[] parameterTypes,
                                    Class[] exceptionTypes) {
        e.begin_method(access,
                       new Signature(name, 
                                     Type.getType(returnType),
                                     TypeUtils.getTypes(parameterTypes)),
                       TypeUtils.getTypes(exceptionTypes));
    }

    public static void begin_method(Emitter e, Method method) {
        begin_method(e, method, getDefaultModifiers(method.getModifiers()));
    }

    public static void begin_method(Emitter e, Method method, int modifiers) {
        e.begin_method(modifiers,
                       ReflectUtils.getSignature(method),
                       TypeUtils.getTypes(method.getExceptionTypes()));
    }

    public static void getfield(Emitter e,Field field) {
        int opcode = Modifier.isStatic(field.getModifiers()) ? Constants.GETSTATIC : Constants.GETFIELD;
        fieldHelper(e, opcode, field);
    }
    
    public static void putfield(Emitter e, Field field) {
        int opcode = Modifier.isStatic(field.getModifiers()) ? Constants.PUTSTATIC : Constants.PUTFIELD;
        fieldHelper(e, opcode, field);
    }

    private static void fieldHelper(Emitter e, int opcode, Field field) {
        e.emit_field(opcode,
                     Type.getType(field.getDeclaringClass()),
                     field.getName(),
                     Type.getType(field.getType()));
    }

    public static void invoke(Emitter e, Method method) {
        Type owner = Type.getType(method.getDeclaringClass());
        Signature sig = ReflectUtils.getSignature(method);
        if (method.getDeclaringClass().isInterface()) {
            e.invoke_interface(owner, sig);
        } else if (Modifier.isStatic(method.getModifiers())) {
            e.invoke_static(owner, sig);
        } else {
            e.invoke_virtual(owner, sig);
        }
    }

    public static void invoke(Emitter e, Constructor constructor) {
        e.invoke_constructor(Type.getType(constructor.getDeclaringClass()),
                             ReflectUtils.getSignature(constructor));
    }

     public static void super_invoke(Emitter e, Method method) {
         e.super_invoke(ReflectUtils.getSignature(method));
     }

    public static void super_invoke(Emitter e, Constructor constructor) {
        e.super_invoke_constructor(ReflectUtils.getSignature(constructor));
    }
    
    public static int getDefaultModifiers(int modifiers) {
        return Constants.ACC_FINAL
            | (modifiers
               & ~Constants.ACC_ABSTRACT
               & ~Constants.ACC_NATIVE
               & ~Constants.ACC_SYNCHRONIZED);
    }
    
    private interface ParameterTyper {
        Class[] getParameterTypes(Object member);
    }

    public static void method_switch(Emitter e,
                                     Method[] methods,
                                     ObjectSwitchCallback callback) throws Exception {
        member_switch_helper(e, Arrays.asList(methods), callback, true, new ParameterTyper() {
            public Class[] getParameterTypes(Object member) {
                return ((Method)member).getParameterTypes();
            }
        });
    }

    public static void constructor_switch(Emitter e,
                                          Constructor[] cstructs,
                                          ObjectSwitchCallback callback) throws Exception {
        member_switch_helper(e, Arrays.asList(cstructs), callback, false, new ParameterTyper() {
            public Class[] getParameterTypes(Object member) {
                return ((Constructor)member).getParameterTypes();
            }
        });
    }

    private static void member_switch_helper(final Emitter e,
                                             List members,
                                             final ObjectSwitchCallback callback,
                                             boolean useName,
                                             final ParameterTyper typer) throws Exception {
        final Map cache = new HashMap();
        final ParameterTyper cached = new ParameterTyper() {
            public Class[] getParameterTypes(Object member) {
                Class[] types = (Class[])cache.get(member);
                if (types == null) {
                    cache.put(member, types = typer.getParameterTypes(member));
                }
                return types;
            }
        };
        final Label def = e.make_label();
        final Label end = e.make_label();
        if (useName) {
            e.swap();
            final Map buckets = CollectionUtils.bucket(members, new Transformer() {
                public Object transform(Object value) {
                    return ((Member)value).getName();
                }
            });
            String[] names = (String[])buckets.keySet().toArray(new String[buckets.size()]);
            ComplexOps.string_switch_hash(e, names, new ObjectSwitchCallback() {
                public void processCase(Object key, Label dontUseEnd) throws Exception {
                    member_helper_size(e, (List)buckets.get(key), callback, cached, def, end);
                }
                public void processDefault() throws Exception {
                    e.goTo(def);
                }
            });
        } else {
            member_helper_size(e, members, callback, cached, def, end);
        }
        e.mark(def);
        e.pop();
        callback.processDefault();
        e.mark(end);
    }

    private static void member_helper_size(final Emitter e,
                                           List members,
                                           final ObjectSwitchCallback callback,
                                           final ParameterTyper typer,
                                           final Label def,
                                           final Label end) throws Exception {
        final Map buckets = CollectionUtils.bucket(members, new Transformer() {
            public Object transform(Object value) {
                return new Integer(typer.getParameterTypes(value).length);
            }
        });
        e.dup();
        e.arraylength();
        e.process_switch(ComplexOps.getSwitchKeys(buckets), new ProcessSwitchCallback() {
            public void processCase(int key, Label dontUseEnd) throws Exception {
                List bucket = (List)buckets.get(new Integer(key));
                Class[] types = typer.getParameterTypes(bucket.get(0));
                member_helper_type(e, bucket, callback, typer, def, end, new TinyBitSet());
            }
            public void processDefault() throws Exception {
                e.goTo(def);
            }
        });
    }

    private static void member_helper_type(final Emitter e,
                                           List members,
                                           final ObjectSwitchCallback callback,
                                           final ParameterTyper typer,
                                           final Label def,
                                           final Label end,
                                           final TinyBitSet checked) throws Exception {
        if (members.size() == 1) {
            // need to check classes that have not already been checked via switches
            Member member = (Member)members.get(0);
            Class[] types = typer.getParameterTypes(member);
            for (int i = 0; i < types.length; i++) {
                if (checked == null || !checked.get(i)) {
                    e.dup();
                    e.aaload(i);
                    e.invoke_virtual(Constants.TYPE_CLASS, GET_NAME);
                    e.push(types[i].getName());
                    e.invoke_virtual(Constants.TYPE_OBJECT, EQUALS);
                    e.if_jump(e.EQ, def);
                }
            }
            e.pop();
            callback.processCase(member, end);
        } else {
            // choose the index that has the best chance of uniquely identifying member
            Class[] example = typer.getParameterTypes(members.get(0));
            Map buckets = null;
            int index = -1;
            for (int i = 0; i < example.length; i++) {
                final int j = i;
                Map test = CollectionUtils.bucket(members, new Transformer() {
                    public Object transform(Object value) {
                        return typer.getParameterTypes(value)[j].getName();
                    }
                });
                if (buckets == null || test.size() > buckets.size()) {
                    buckets = test;
                    index = i;
                }
            }
            if (buckets == null) {
                // TODO: switch by returnType
                // must have two methods with same name, types, and different return types
                e.goTo(def);
            } else {
                checked.set(index);

                e.dup();
                e.aaload(index);
                e.invoke_virtual(Constants.TYPE_CLASS, GET_NAME);

                final Map fbuckets = buckets;
                String[] names = (String[])buckets.keySet().toArray(new String[buckets.size()]);
                ComplexOps.string_switch_hash(e, names, new ObjectSwitchCallback() {
                    public void processCase(Object key, Label dontUseEnd) throws Exception {
                        member_helper_type(e, (List)fbuckets.get(key), callback, typer, def, end, checked);
                    }
                    public void processDefault() throws Exception {
                        e.goTo(def);
                    }
                });
            }
        }
    }

    public static void push(Emitter e, Object[] array) {
        e.push(array.length);
        e.newarray(Type.getType(array.getClass().getComponentType()));
        for (int i = 0; i < array.length; i++) {
            e.dup();
            e.push(i);
            push_object(e, array[i]);
            e.aastore();
        }
    }
    
    public static void push_object(Emitter e, Object obj) {
        if (obj == null) {
            e.aconst_null();
        } else {
            Class type = obj.getClass();
            if (type.isArray()) {
                push(e, (Object[])obj);
            } else if (obj instanceof String) {
                e.push((String)obj);
            } else if (obj instanceof Class) {
                ComplexOps.load_class(e, Type.getType((Class)obj));
            } else if (obj instanceof BigInteger) {
                e.new_instance(BIG_INTEGER);
                e.dup();
                e.push(obj.toString());
                e.invoke_constructor(BIG_INTEGER);
            } else if (obj instanceof BigDecimal) {
                e.new_instance(BIG_DECIMAL);
                e.dup();
                e.push(obj.toString());
                e.invoke_constructor(BIG_DECIMAL);
            } else {
                throw new IllegalArgumentException("unknown type: " + obj.getClass());
            }
        }
    }
}