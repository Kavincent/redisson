/**
 * Copyright (c) 2013-2020 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson.liveobject.core;

import org.redisson.*;
import org.redisson.api.*;
import org.redisson.api.annotation.REntity;
import org.redisson.api.annotation.RObjectField;
import org.redisson.client.codec.Codec;
import org.redisson.codec.DefaultReferenceCodecProvider;
import org.redisson.codec.ReferenceCodecProvider;
import org.redisson.config.Config;
import org.redisson.liveobject.misc.ClassUtils;
import org.redisson.liveobject.resolver.NamingScheme;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentMap;

/**
 * 
 * @author Rui Gu
 * @author Nikita Koksharov
 *
 */
public class RedissonObjectBuilder {

    private static final Map<Class<?>, Class<? extends RObject>> SUPPORTED_CLASS_MAPPING = new LinkedHashMap<>();
    private static final Map<Class<?>, CodecMethodRef> REFERENCES = new HashMap<>();
    
    static {
        SUPPORTED_CLASS_MAPPING.put(SortedSet.class,      RedissonSortedSet.class);
        SUPPORTED_CLASS_MAPPING.put(Set.class,            RedissonSet.class);
        SUPPORTED_CLASS_MAPPING.put(ConcurrentMap.class,  RedissonMap.class);
        SUPPORTED_CLASS_MAPPING.put(Map.class,            RedissonMap.class);
        SUPPORTED_CLASS_MAPPING.put(BlockingDeque.class,  RedissonBlockingDeque.class);
        SUPPORTED_CLASS_MAPPING.put(Deque.class,          RedissonDeque.class);
        SUPPORTED_CLASS_MAPPING.put(BlockingQueue.class,  RedissonBlockingQueue.class);
        SUPPORTED_CLASS_MAPPING.put(Queue.class,          RedissonQueue.class);
        SUPPORTED_CLASS_MAPPING.put(List.class,           RedissonList.class);
        
        fillCodecMethods(REFERENCES, RedissonClient.class, RObject.class);
        fillCodecMethods(REFERENCES, RedissonReactiveClient.class, RObjectReactive.class);
        fillCodecMethods(REFERENCES, RedissonRxClient.class, RObjectRx.class);
    }

    private final Config config;
    private RedissonClient redisson;
    private RedissonReactiveClient redissonReactive;
    private RedissonRxClient redissonRx;
    
    public static class CodecMethodRef {

        Method defaultCodecMethod;
        Method customCodecMethod;

        Method get(boolean value) {
            if (value) {
                return defaultCodecMethod;
            }
            return customCodecMethod;
        }
    }
    
    private final ReferenceCodecProvider codecProvider = new DefaultReferenceCodecProvider();
    
    public RedissonObjectBuilder(RedissonClient redisson) {
        super();
        this.config = redisson.getConfig();
        this.redisson = redisson;

        Codec codec = config.getCodec();
        codecProvider.registerCodec((Class<Codec>) codec.getClass(), codec);
    }

    public RedissonObjectBuilder(RedissonReactiveClient redissonReactive) {
        super();
        this.config = redissonReactive.getConfig();
        this.redissonReactive = redissonReactive;

        Codec codec = config.getCodec();
        codecProvider.registerCodec((Class<Codec>) codec.getClass(), codec);
    }

    public RedissonObjectBuilder(RedissonRxClient redissonRx) {
        super();
        this.config = redissonRx.getConfig();
        this.redissonRx = redissonRx;

        Codec codec = config.getCodec();
        codecProvider.registerCodec((Class<Codec>) codec.getClass(), codec);
    }

    public void storeAsync(RObject ar, String fieldName, RMap<String, Object> liveMap) {
        Codec codec = ar.getCodec();
        if (codec != null) {
            codecProvider.registerCodec((Class) codec.getClass(), codec);
        }
        liveMap.fastPutAsync(fieldName,
                new RedissonReference(ar.getClass(), ar.getName(), codec));
    }
    
    public void store(RObject ar, String fieldName, RMap<String, Object> liveMap) {
        Codec codec = ar.getCodec();
        if (codec != null) {
            codecProvider.registerCodec((Class) codec.getClass(), codec);
        }
        liveMap.fastPut(fieldName,
                new RedissonReference(ar.getClass(), ar.getName(), codec));
    }
    
    public RObject createObject(Object id, Class<?> clazz, Class<?> fieldType, String fieldName) {
        Class<? extends RObject> mappedClass = getMappedClass(fieldType);
        try {
            if (mappedClass != null) {
                Codec fieldCodec = getFieldCodec(clazz, mappedClass, fieldName);
                NamingScheme fieldNamingScheme = getNamingScheme(clazz, fieldCodec);
                String referenceName = fieldNamingScheme.getFieldReferenceName(clazz, id, mappedClass, fieldName);
                
                return createRObject(redisson, mappedClass, referenceName, fieldCodec);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
        return null;
    }
    
    /**
     * WARNING: rEntity has to be the class of @This object.
     */
    private Codec getFieldCodec(Class<?> rEntity, Class<? extends RObject> rObjectClass, String fieldName) throws ReflectiveOperationException {
        Field field = ClassUtils.getDeclaredField(rEntity, fieldName);
        if (field.isAnnotationPresent(RObjectField.class)) {
            RObjectField anno = field.getAnnotation(RObjectField.class);
            return codecProvider.getCodec(anno, rEntity, rObjectClass, fieldName, config);
        } else {
            REntity anno = ClassUtils.getAnnotation(rEntity, REntity.class);
            return codecProvider.getCodec(anno, (Class<?>) rEntity, config);
        }
    }
    
    public NamingScheme getNamingScheme(Class<?> entityClass) {
        REntity anno = ClassUtils.getAnnotation(entityClass, REntity.class);
        Codec codec = codecProvider.getCodec(anno, entityClass, config);
        return getNamingScheme(entityClass, codec);
    }
    
    public NamingScheme getNamingScheme(Class<?> rEntity, Codec c) {
        REntity anno = ClassUtils.getAnnotation(rEntity, REntity.class);
        try {
            return anno.namingScheme().getDeclaredConstructor(Codec.class).newInstance(c);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private Class<? extends RObject> getMappedClass(Class<?> cls) {
        for (Entry<Class<?>, Class<? extends RObject>> entrySet : SUPPORTED_CLASS_MAPPING.entrySet()) {
            if (entrySet.getKey().isAssignableFrom(cls)) {
                return entrySet.getValue();
            }
        }
        return null;
    }
    
    private static void fillCodecMethods(Map<Class<?>, CodecMethodRef> map, Class<?> clientClazz, Class<?> objectClazz) {
        for (Method method : clientClazz.getDeclaredMethods()) {
            if (!method.getReturnType().equals(Void.TYPE)
                    && objectClazz.isAssignableFrom(method.getReturnType())
                    && method.getName().startsWith("get")) {
                Class<?> cls = method.getReturnType();
                if (!map.containsKey(cls)) {
                    map.put(cls, new CodecMethodRef());
                }
                CodecMethodRef builder = map.get(cls);
                if (method.getParameterTypes().length == 2 //first param is name, second param is codec.
                        && Codec.class.isAssignableFrom(method.getParameterTypes()[1])) {
                    builder.customCodecMethod = method;
                } else if (method.getParameterTypes().length == 1) {
                    builder.defaultCodecMethod = method;
                }
            }
        }
    }

    public Object fromReference(RedissonReference rr) throws ReflectiveOperationException {
        if (rr.getReferenceType() == RedissonReference.ReferenceType.REACTIVE) {
            return fromReference(redissonReactive, rr);
        } else if (rr.getReferenceType() == RedissonReference.ReferenceType.RXJAVA) {
            return fromReference(redissonRx, rr);
        }
        return fromReference(redisson, rr);
    }
    
    private Object fromReference(RedissonClient redisson, RedissonReference rr) throws ReflectiveOperationException {
        Class<? extends Object> type = rr.getType();
        if (type != null) {
            if (ClassUtils.isAnnotationPresent(type, REntity.class)) {
                RedissonLiveObjectService liveObjectService = (RedissonLiveObjectService) redisson.getLiveObjectService();
                
                NamingScheme ns = getNamingScheme(type);
                Object id = ns.resolveId(rr.getKeyName());
                return liveObjectService.createLiveObject(type, id);
            }
        }

        return getObject(redisson, rr, type, codecProvider);
    }

    private Object getObject(Object redisson, RedissonReference rr, Class<? extends Object> type,
            ReferenceCodecProvider codecProvider) throws ReflectiveOperationException {
        if (type != null) {
            CodecMethodRef b = REFERENCES.get(type);
            if (b == null && type.getInterfaces().length > 0) {
                type = type.getInterfaces()[0];
            }
            b = REFERENCES.get(type);
            if (b != null) {
                Method builder = b.get(isDefaultCodec(rr));
                if (isDefaultCodec(rr)) {
                    return builder.invoke(redisson, rr.getKeyName());
                }
                return builder.invoke(redisson, rr.getKeyName(), codecProvider.getCodec(rr.getCodecType()));
            }
        }
        throw new ClassNotFoundException("No RObject is found to match class type of " + rr.getTypeName() + " with codec type of " + rr.getCodecName());
    }
    
    private boolean isDefaultCodec(RedissonReference rr) {
        return rr.getCodec() == null;
    }

    private Object fromReference(RedissonRxClient redisson, RedissonReference rr) throws ReflectiveOperationException {
        Class<? extends Object> type = rr.getRxJavaType();
        /**
         * Live Object from reference in rxjava client is not supported yet.
         */
        return getObject(redisson, rr, type, codecProvider);
    }
    
    private Object fromReference(RedissonReactiveClient redisson, RedissonReference rr) throws ReflectiveOperationException {
        Class<? extends Object> type = rr.getReactiveType();
        /**
         * Live Object from reference in reactive client is not supported yet.
         */
        return getObject(redisson, rr, type, codecProvider);
    }

    public RedissonReference toReference(Object object) {
        if (object != null && ClassUtils.isAnnotationPresent(object.getClass(), REntity.class)) {
            throw new IllegalArgumentException("REntity should be attached to Redisson before save");
        }
        
        if (object instanceof RObject && !(object instanceof RLiveObject)) {
            Class<?> clazz = object.getClass().getInterfaces()[0];
            
            RObject rObject = (RObject) object;
            if (rObject.getCodec() != null) {
                codecProvider.registerCodec((Class) rObject.getCodec().getClass(), rObject.getCodec());
            }
            return new RedissonReference(clazz, rObject.getName(), rObject.getCodec());
        }
        if (object instanceof RObjectReactive && !(object instanceof RLiveObject)) {
            Class<?> clazz = object.getClass().getInterfaces()[0];

            RObjectReactive rObject = (RObjectReactive) object;
            if (rObject.getCodec() != null) {
                codecProvider.registerCodec((Class) rObject.getCodec().getClass(), rObject.getCodec());
            }
            return new RedissonReference(clazz, rObject.getName(), rObject.getCodec());
        }
        if (object instanceof RObjectRx && !(object instanceof RLiveObject)) {
            Class<?> clazz = object.getClass().getInterfaces()[0];

            RObjectRx rObject = (RObjectRx) object;
            if (rObject.getCodec() != null) {
                codecProvider.registerCodec((Class) rObject.getCodec().getClass(), rObject.getCodec());
            }
            return new RedissonReference(clazz, rObject.getName(), rObject.getCodec());
        }

        try {
            if (object instanceof RLiveObject) {
                Class<? extends Object> rEntity = object.getClass().getSuperclass();
                NamingScheme ns = getNamingScheme(rEntity);

                return new RedissonReference(rEntity,
                        ns.getName(rEntity, ((RLiveObject) object).getLiveObjectId()));
            }
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
        return null;
    }

    private <T extends RObject, K extends Codec> T createRObject(RedissonClient redisson, Class<T> expectedType, String name, K codec) throws ReflectiveOperationException {
        List<Class<?>> interfaces = Arrays.asList(expectedType.getInterfaces());
        for (Class<?> iType : interfaces) {
            if (REFERENCES.containsKey(iType)) {// user cache to speed up things a little.
                Method builder = REFERENCES.get(iType).get(codec != null);
                if (codec != null) {
                    return (T) builder.invoke(redisson, name);
                }
                return (T) builder.invoke(redisson, name, codec);
            }
        }
        
        String type = null;
        if (expectedType != null) {
            type = expectedType.getName();
        }
        String codecName = null;
        if (codec != null) {
            codecName = codec.getClass().getName();
        }
        throw new ClassNotFoundException("No RObject is found to match class type of " + type + " with codec type of " + codecName);
    }
    
}
