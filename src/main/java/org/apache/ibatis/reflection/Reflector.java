/**
 *    Copyright 2009-2019 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.reflection;

import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.invoker.SetFieldInvoker;
import org.apache.ibatis.reflection.property.PropertyNamer;

import java.lang.reflect.*;
import java.util.*;
import java.util.Map.Entry;

/**
 * This class represents a cached set of class definition information that
 * allows for easy mapping between property names and getter/setter methods.
 *
 * @author Clinton Begin
 */
public class Reflector {

    /**
     * 对应的类
     */
    private final Class<?> type;
    /**
     * 可读属性数组
     */
    private final String[] readablePropertyNames;
    /**
     * 可写属性集合
     */
    private final String[] writablePropertyNames;
    /**
     * 属性对应的 setting 方法的映射。
     * <p>
     * key 为属性名称
     * value 为 Invoker 对象
     */
    private final Map<String, Invoker> setMethods = new HashMap<>();
    /**
     * 属性对应的 getting 方法的映射。
     * <p>
     * key 为属性名称
     * value 为 Invoker 对象
     */
    private final Map<String, Invoker> getMethods = new HashMap<>();
    /**
     * 属性对应的 setting 方法的方法参数类型的映射。{@link #setMethods}
     * <p>
     * key 为属性名称
     * value 为方法参数类型
     */
    private final Map<String, Class<?>> setTypes = new HashMap<>();
    /**
     * 属性对应的 getting 方法的返回值类型的映射。{@link #getMethods}
     * <p>
     * key 为属性名称
     * value 为返回值的类型
     */
    private final Map<String, Class<?>> getTypes = new HashMap<>();

    /**
     * 默认构造方法
     */
    private Constructor<?> defaultConstructor;
    /**
     * 不区分大小写的属性集合
     */
    private Map<String, String> caseInsensitivePropertyMap = new HashMap<>();

    public Reflector(Class<?> clazz) {
        type = clazz;
        // <1> 初始化 defaultConstructor
        addDefaultConstructor(clazz);
        // <2> // 初始化 getMethods 和 getTypes ，通过遍历 getting 方法
        addGetMethods(clazz);
        // <3> // 初始化 setMethods 和 setTypes ，通过遍历 setting 方法。
        addSetMethods(clazz);
        // <4> // 初始化 getMethods + getTypes 和 setMethods + setTypes ，通过遍历 fields 属性。
        addFields(clazz);
        // <5> 初始化 readablePropertyNames、writeablePropertyNames、caseInsensitivePropertyMap 属性

        readablePropertyNames = getMethods.keySet().toArray(new String[getMethods.keySet().size()]);
        writablePropertyNames = setMethods.keySet().toArray(new String[setMethods.keySet().size()]);
        for (String propName : readablePropertyNames) {
            caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
        }
        for (String propName : writablePropertyNames) {
            caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
        }
    }

    /**
     * 查找默认无参构造方法
     *
     * @param clazz
     */
    private void addDefaultConstructor(Class<?> clazz) {
        Constructor<?>[] consts = clazz.getDeclaredConstructors();
        for (Constructor<?> constructor : consts) {
            // 判断无参的构造方法
            if (constructor.getParameterTypes().length == 0) {
                if (canControlMemberAccessible()) {
                    try {
                        constructor.setAccessible(true);
                    } catch (SecurityException e) {
                        e.printStackTrace();
                    }
                }
                if (constructor.isAccessible()) {
                    this.defaultConstructor = constructor;
                }
            }
        }
    }

    private void addGetMethods(Class<?> cls) {
        // <1> 属性与其 getting 方法的映射。
        Map<String, List<Method>> conflictingGetters = new HashMap<>();
        // <2> 获得所有方法
        Method[] methods = getClassMethods(cls);
        for (Method method : methods) {
            // <3.1> 参数大于 0 ，说明不是 getting 方法，忽略
            if (method.getParameterTypes().length > 0) {
                continue;
            }
            String name = method.getName();
            // <3.2> 以 get 和 is 方法名开头，说明是 getting 方法
            if ((name.startsWith("get") && name.length() > 3)
                    || (name.startsWith("is") && name.length() > 2)) {
                name = PropertyNamer.methodToProperty(name);
                addMethodConflict(conflictingGetters, name, method);
            }
        }
        // <4> 解决 getting 冲突方法
        resolveGetterConflicts(conflictingGetters);
    }

    private void resolveGetterConflicts(Map<String, List<Method>> conflictingGetters) {
        // 遍历每个属性，查找其最匹配的方法。因为子类可以覆写父类的方法，所以一个属性，可能对应多个 getting 方法

        for (Entry<String, List<Method>> entry : conflictingGetters.entrySet()) {
            Method winner = null;
            String propName = entry.getKey();
            for (Method candidate : entry.getValue()) {
                if (winner == null) {
                    winner = candidate;
                    continue;
                }
                Class<?> winnerType = winner.getReturnType();
                Class<?> candidateType = candidate.getReturnType();
                if (candidateType.equals(winnerType)) {
                    if (!boolean.class.equals(candidateType)) {
                        throw new ReflectionException(
                                "Illegal overloaded getter method with ambiguous type for property "
                                        + propName + " in class " + winner.getDeclaringClass()
                                        + ". This breaks the JavaBeans specification and can cause unpredictable results.");
                    } else if (candidate.getName().startsWith("is")) {
                        winner = candidate;
                    }
                } else if (candidateType.isAssignableFrom(winnerType)) {
                    // OK getter type is descendant
                } else if (winnerType.isAssignableFrom(candidateType)) {
                    winner = candidate;
                } else {
                    throw new ReflectionException(
                            "Illegal overloaded getter method with ambiguous type for property "
                                    + propName + " in class " + winner.getDeclaringClass()
                                    + ". This breaks the JavaBeans specification and can cause unpredictable results.");
                }
            }
            addGetMethod(propName, winner);
        }
    }

    private void addGetMethod(String name, Method method) {
        // <2.1> 判断是合理的属性名
        if (isValidPropertyName(name)) {
            getMethods.put(name, new MethodInvoker(method));
            Type returnType = TypeParameterResolver.resolveReturnType(method, type);
            getTypes.put(name, typeToClass(returnType));
        }
    }

    private void addSetMethods(Class<?> cls) {
        Map<String, List<Method>> conflictingSetters = new HashMap<>();
        Method[] methods = getClassMethods(cls);
        for (Method method : methods) {
            String name = method.getName();
            if (name.startsWith("set") && name.length() > 3) {
                if (method.getParameterTypes().length == 1) {
                    name = PropertyNamer.methodToProperty(name);
                    addMethodConflict(conflictingSetters, name, method);
                }
            }
        }
        resolveSetterConflicts(conflictingSetters);
    }

    private void addMethodConflict(Map<String, List<Method>> conflictingMethods, String name, Method method) {
        List<Method> list = conflictingMethods.computeIfAbsent(name, k -> new ArrayList<>());
        list.add(method);
    }

    private void resolveSetterConflicts(Map<String, List<Method>> conflictingSetters) {
        // 遍历每个属性，查找其最匹配的方法。因为子类可以覆写父类的方法，所以一个属性，可能对应多个 setting 方法
        for (String propName : conflictingSetters.keySet()) {
            List<Method> setters = conflictingSetters.get(propName);
            Class<?> getterType = getTypes.get(propName);
            Method match = null;
            ReflectionException exception = null;
            for (Method setter : setters) {
                Class<?> paramType = setter.getParameterTypes()[0];
                if (paramType.equals(getterType)) {
                    // should be the best match
                    match = setter;
                    break;
                }
                if (exception == null) {
                    try {
                        match = pickBetterSetter(match, setter, propName);
                    } catch (ReflectionException e) {
                        // there could still be the 'best match'
                        match = null;
                        exception = e;
                    }
                }
            }
            // <2> 添加到 setMethods 和 setTypes 中
            if (match == null) {
                throw exception;
            } else {
                addSetMethod(propName, match);
            }
        }
    }

    private Method pickBetterSetter(Method setter1, Method setter2, String property) {
        if (setter1 == null) {
            return setter2;
        }
        Class<?> paramType1 = setter1.getParameterTypes()[0];
        Class<?> paramType2 = setter2.getParameterTypes()[0];
        if (paramType1.isAssignableFrom(paramType2)) {
            return setter2;
        } else if (paramType2.isAssignableFrom(paramType1)) {
            return setter1;
        }
        throw new ReflectionException("Ambiguous setters defined for property '" + property + "' in class '"
                + setter2.getDeclaringClass() + "' with types '" + paramType1.getName() + "' and '"
                + paramType2.getName() + "'.");
    }

    private void addSetMethod(String name, Method method) {
        if (isValidPropertyName(name)) {
            // 添加到 setMethods 中
            setMethods.put(name, new MethodInvoker(method));
            Type[] paramTypes = TypeParameterResolver.resolveParamTypes(method, type);
            // 添加到 setTypes 中
            setTypes.put(name, typeToClass(paramTypes[0]));
        }
    }

    private Class<?> typeToClass(Type src) {
        Class<?> result = null;
        // 普通类型，直接使用类
        if (src instanceof Class) {
            result = (Class<?>) src;
            // 泛型类型，使用泛型
        } else if (src instanceof ParameterizedType) {
            result = (Class<?>) ((ParameterizedType) src).getRawType();
            // 泛型数组，获得具体类
        } else if (src instanceof GenericArrayType) {
            Type componentType = ((GenericArrayType) src).getGenericComponentType();
            if (componentType instanceof Class) {// 普通类型
                result = Array.newInstance((Class<?>) componentType, 0).getClass();
            } else {
                Class<?> componentClass = typeToClass(componentType);
                result = Array.newInstance(componentClass, 0).getClass();
            }
        }
        // 都不符合，使用 Object 类
        if (result == null) {
            result = Object.class;
        }
        return result;
    }

    private void addFields(Class<?> clazz) {
        Field[] fields = clazz.getDeclaredFields();
        for (Field field : fields) {
            if (!setMethods.containsKey(field.getName())) {
                // issue #379 - removed the check for final because JDK 1.5 allows
                // modification of final fields through reflection (JSR-133). (JGB)
                // pr #16 - final static can only be set by the classloader
                int modifiers = field.getModifiers();
                if (!(Modifier.isFinal(modifiers) && Modifier.isStatic(modifiers))) {
                    addSetField(field);
                }
            }
            if (!getMethods.containsKey(field.getName())) {
                addGetField(field);
            }
        }
        if (clazz.getSuperclass() != null) {
            addFields(clazz.getSuperclass());
        }
    }

    private void addSetField(Field field) {
        if (isValidPropertyName(field.getName())) {
            setMethods.put(field.getName(), new SetFieldInvoker(field));
            Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
            setTypes.put(field.getName(), typeToClass(fieldType));
        }
    }

    private void addGetField(Field field) {
        if (isValidPropertyName(field.getName())) {
            getMethods.put(field.getName(), new GetFieldInvoker(field));
            Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
            getTypes.put(field.getName(), typeToClass(fieldType));
        }
    }

    private boolean isValidPropertyName(String name) {
        return !(name.startsWith("$") || "serialVersionUID".equals(name) || "class".equals(name));
    }

    /**
     * This method returns an array containing all methods
     * declared in this class and any superclass.
     * We use this method, instead of the simpler <code>Class.getMethods()</code>,
     * because we want to look for private methods as well.
     *
     * @param cls The class
     * @return An array containing all methods in this class
     */
    private Method[] getClassMethods(Class<?> cls) {
        // 每个方法签名与该方法的映射
        Map<String, Method> uniqueMethods = new HashMap<>();
        Class<?> currentClass = cls;
        // 循环类，类的父类，类的父类的父类，直到父类为 Object
        while (currentClass != null && currentClass != Object.class) {
            // <1> 记录当前类定义的方法
            addUniqueMethods(uniqueMethods, currentClass.getDeclaredMethods());

            // we also need to look for interface methods -
            // because the class may be abstract
            // <2> 记录接口中定义的方法
            Class<?>[] interfaces = currentClass.getInterfaces();
            for (Class<?> anInterface : interfaces) {
                addUniqueMethods(uniqueMethods, anInterface.getMethods());
            }
            // 获得父类
            currentClass = currentClass.getSuperclass();
        }

        Collection<Method> methods = uniqueMethods.values();

        return methods.toArray(new Method[methods.size()]);
    }

    private void addUniqueMethods(Map<String, Method> uniqueMethods, Method[] methods) {
        for (Method currentMethod : methods) {
//            忽略 bridge 方法，参见 https://www.zhihu.com/question/54895701/answer/141623158 文章
            if (!currentMethod.isBridge()) {
                String signature = getSignature(currentMethod);
                // check to see if the method is already known
                // if it is known, then an extended class must have
                // overridden a method
                if (!uniqueMethods.containsKey(signature)) {
                    uniqueMethods.put(signature, currentMethod);
                }
            }
        }
    }

    /**
     * returnType#方法名:参数名1,参数名2,参数名3
     * 例如：void#checkPackageAccess:java.lang.ClassLoader,boolean 。
     *
     * @param method
     * @return
     */
    private String getSignature(Method method) {
        StringBuilder sb = new StringBuilder();
        Class<?> returnType = method.getReturnType();
        if (returnType != null) {
            sb.append(returnType.getName()).append('#');
        }
        sb.append(method.getName());
        Class<?>[] parameters = method.getParameterTypes();
        for (int i = 0; i < parameters.length; i++) {
            if (i == 0) {
                sb.append(':');
            } else {
                sb.append(',');
            }
            sb.append(parameters[i].getName());
        }
        return sb.toString();
    }

    /**
     * Checks whether can control member accessible.
     *
     * @return If can control member accessible, it return {@literal true}
     * @since 3.5.0
     */
    public static boolean canControlMemberAccessible() {
        try {
            SecurityManager securityManager = System.getSecurityManager();
            if (null != securityManager) {
                securityManager.checkPermission(new ReflectPermission("suppressAccessChecks"));
            }
        } catch (SecurityException e) {
            return false;
        }
        return true;
    }

    /**
     * Gets the name of the class the instance provides information for.
     *
     * @return The class name
     */
    public Class<?> getType() {
        return type;
    }

    public Constructor<?> getDefaultConstructor() {
        if (defaultConstructor != null) {
            return defaultConstructor;
        } else {
            throw new ReflectionException("There is no default constructor for " + type);
        }
    }

    public boolean hasDefaultConstructor() {
        return defaultConstructor != null;
    }

    public Invoker getSetInvoker(String propertyName) {
        Invoker method = setMethods.get(propertyName);
        if (method == null) {
            throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
        }
        return method;
    }

    public Invoker getGetInvoker(String propertyName) {
        Invoker method = getMethods.get(propertyName);
        if (method == null) {
            throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
        }
        return method;
    }

    /**
     * Gets the type for a property setter.
     *
     * @param propertyName - the name of the property
     * @return The Class of the property setter
     */
    public Class<?> getSetterType(String propertyName) {
        Class<?> clazz = setTypes.get(propertyName);
        if (clazz == null) {
            throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
        }
        return clazz;
    }

    /**
     * Gets the type for a property getter.
     *
     * @param propertyName - the name of the property
     * @return The Class of the property getter
     */
    public Class<?> getGetterType(String propertyName) {
        Class<?> clazz = getTypes.get(propertyName);
        if (clazz == null) {
            throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
        }
        return clazz;
    }

    /**
     * Gets an array of the readable properties for an object.
     *
     * @return The array
     */
    public String[] getGetablePropertyNames() {
        return readablePropertyNames;
    }

    /**
     * Gets an array of the writable properties for an object.
     *
     * @return The array
     */
    public String[] getSetablePropertyNames() {
        return writablePropertyNames;
    }

    /**
     * Check to see if a class has a writable property by name.
     *
     * @param propertyName - the name of the property to check
     * @return True if the object has a writable property by the name
     */
    public boolean hasSetter(String propertyName) {
        return setMethods.keySet().contains(propertyName);
    }

    /**
     * Check to see if a class has a readable property by name.
     *
     * @param propertyName - the name of the property to check
     * @return True if the object has a readable property by the name
     */
    public boolean hasGetter(String propertyName) {
        return getMethods.keySet().contains(propertyName);
    }

    public String findPropertyName(String name) {
        return caseInsensitivePropertyMap.get(name.toUpperCase(Locale.ENGLISH));
    }
}
