package com.amateur.encrypt.utils;

import com.amateur.encrypt.annotation.DecryptField;
import com.amateur.encrypt.annotation.EncryptField;
import com.amateur.encrypt.constant.EncDecType;

import java.lang.annotation.Annotation;
import java.lang.ref.SoftReference;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * @author yeyu
 * @since 2022/2/23 10:17
 */
@SuppressWarnings("all")
public abstract class AbstractEncDec {

    private final static int MAX_STACK_LENGTH = 100;

    private final static Map<Class<?>, SoftReference<List<Field>>> cache = new ConcurrentHashMap<>();

    protected abstract String encrypt(String original);

    protected abstract String decrypt(String original);

    private boolean typeCheck(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }
        // Java 自带的类的classLoader都为null
        return clazz.getClassLoader() != null && !clazz.isEnum();
    }

    private boolean regexCheck(String str, Annotation annotation) {
        if (annotation instanceof EncryptField) {
            String regex = ((EncryptField) annotation).regex();
            return Pattern.matches(regex, str);
        } else if (annotation instanceof DecryptField) {
            String regex = ((DecryptField) annotation).regex();
            return Pattern.matches(regex, str);
        }
        return false;
    }

    // 处理流程: Obj -> 是否Map,List --是-->①遍历值 -->从头开始
    //                             --否-->②遍历属性值-->是否为加了注解得String字符串--是-->加密解密
    //                                                                       --否-->是否是Map,List--是-->①
    //                                                                                         --否-->是否自定义类--是-->②
    //                                                                                                         --否-->结束
    private void recursive(Object obj,
                           Class<? extends Annotation> annotationClass,
                           Set<Object> set,
                           EncDecType type) throws Exception {
        if (obj == null || annotationClass == null) {
            return;
        }
        if (obj instanceof Collection) {
            Collection list = (Collection) obj;
            for (Object item : list) {
                recursive(item, annotationClass, set, type);
            }
        } else if (obj instanceof Map) {
            Map map = (Map) obj;
            for (Object value : map.values()) {
                recursive(value, annotationClass, set, type);
            }
        } else if (typeCheck(obj.getClass())) {
            if (findInCache(type,obj.getClass(),obj)) {
                return;
            }
            for (Field field : findFileds(obj.getClass())) {
                field.setAccessible(true);
                doRecursive(obj, field.get(obj), field, annotationClass, 0, set, type);
            }
        }
    }


    private void doRecursive(Object source,
                             Object fieldObj,
                             Field field,
                             Class<? extends Annotation> annotationClass,
                             int count,
                             Set<Object> set,
                             EncDecType type) throws Exception {
        if (fieldObj == null) {
            return;
        }
        if (count > MAX_STACK_LENGTH) {
            throw new RuntimeException("递归过深");
        }
        if (fieldObj instanceof String) {
            if (field.isAnnotationPresent(annotationClass)
                    && !regexCheck(fieldObj.toString(), field.getAnnotation(annotationClass))) {
                doForField(field, source, fieldObj, type);
                putInCache(source.getClass(), field);
            }
        } else if ((fieldObj instanceof Collection) || (fieldObj instanceof Map)) {
            recursive(fieldObj, annotationClass, set, type);
        } else if (typeCheck(fieldObj.getClass())) {
            putInCache(fieldObj.getClass(),field);
            for (Field inFiled : findFileds(fieldObj.getClass())) {
                inFiled.setAccessible(true);
                if (set.contains(fieldObj)) {
                    return;
                } else {
                    set.add(fieldObj);
                }
                doRecursive(fieldObj, inFiled.get(fieldObj), inFiled, annotationClass, ++count, set, type);
            }
        }
    }

    private void doForField(Field field, Object source, Object fieldObj, EncDecType type) throws Exception {
        String original = (String) fieldObj;
        String after = original;
        if (type.equals(EncDecType.ENCRYPT)) {
            after = encrypt(original);
        } else if (type.equals(EncDecType.DECRYPT)) {
            after = decrypt(original);
        }
        field.set(source, after);
    }

    private void putInCache(Class<?> clazz, Field field) {
        if (cache.containsKey(clazz)) {
            cache.get(clazz).get().add(field);
        } else {
            List<Field> list = new ArrayList<>();
            list.add(field);
            SoftReference<List<Field>> value = new SoftReference<>(list);
            cache.put(clazz, value);
        }
    }

    private boolean findInCache(EncDecType type,Class<?> clazz,Object source) throws Exception {
        if (cache.containsKey(clazz)) {
            List<Field> fields = cache.get(clazz).get();
            for (Field field : fields) {
                if (cache.containsKey(field.getType())) {
                    findInCache(type,field.getType(),field.get(source));
                } else {
                    doForField(field,source,field.get(source),type);
                }
            }
            return true;
        }
        return false;
    }

    private Field[] findFileds(Class<?> clazz) {
        List<Field> list = new ArrayList<>();
        do {
            Field[] fields = clazz.getDeclaredFields();
            for (Field field : fields) {
                field.setAccessible(true);
                list.add(field);
            }
        } while ((clazz = clazz.getSuperclass()) != Object.class);
        return list.toArray(new Field[list.size()]);
    }

    public void decryptField(Object obj, Class<? extends Annotation> annotationClass) throws Exception {
        recursive(obj, annotationClass, new HashSet<>(), EncDecType.DECRYPT);
    }

    public void encryptField(Object obj, Class<? extends Annotation> annotationClass) throws Exception {
        recursive(obj, annotationClass, new HashSet<>(), EncDecType.ENCRYPT);
    }

}
