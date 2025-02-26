package com.test.serializer;

import com.google.protobuf.Message;
import com.google.protobuf.Timestamp;
import org.apache.commons.lang3.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Класс, в котором представлена логика сериализации
 *
 * @param <T>
 */

public class UniversalProtobufSerializer<T> implements ProtobufSerializer<T> {
    /**
     * Класс protobuf-сообщения, который будет сериализоваться
     */
    private final Class<? extends Message> protoClass;

    /**
     * Мапа, где ключами являются имена полей, а значениями — методы setFieldName .proto класса
     */
    private final Map<String, Method> setters;

    /**
     * Кэширование для (билдера)
     */
    private static final ConcurrentHashMap<Class<?>, Method> builderMethodCache = new ConcurrentHashMap<>();

    /**
     * Реестр сериализаторов для обработки вложенных объектов
     */
    private final SerializerRegistry serializerRegistry;

    /**
     * Для отображения логов
     */
    private final Logger log = LoggerFactory.getLogger(UniversalProtobufSerializer.class);

    /**
     * Кэширование важных методов при создании объекта
     */
    private final transient Method[] cachedJavaMethods;

    /**
     * @param javaClass          передаваемый Java класс
     * @param protoClass         передаваемый proto класс
     * @param serializerRegistry передаваемый реестр сериализаторов
     */
    public UniversalProtobufSerializer(
            Class<T> javaClass,
            Class<? extends Message> protoClass,
            SerializerRegistry serializerRegistry
    ) {
        this.protoClass = protoClass; // получение proto класса
        this.serializerRegistry = serializerRegistry; // создание переменной для доступа к реестру сериалайзеров

        // кэширование метода
        Method buildermethod = builderMethodCache.computeIfAbsent(
                protoClass,
                clazz -> {
                    try {
                        return clazz.getMethod("newBuilder");
                    } catch (NoSuchMethodException e) {
                        throw new RuntimeException(e);
                    }
                }
        );

        // проверка, что у сгенерированного proto класса есть Builder
        Class<?> builderClass = buildermethod.getReturnType();
        if (!Message.Builder.class.isAssignableFrom(builderClass)) {
            throw new IllegalArgumentException("Provided protoClass does not have a valid Builder class");
        }

        this.cachedJavaMethods = preCacheJavaMethods(javaClass);
        this.setters = buildSetterMap((Class<? extends Message.Builder>) builderClass); // заполнение мапы сеттеров заранее
    }

    /**
     * Метод получения отфильтрованных методов класса
     *
     * @param clazz - proto класс
     * @return
     */
    private Method[] preCacheJavaMethods(Class<T> clazz) {
        return Arrays.stream(clazz.getDeclaredMethods())
                .filter(this::isGetter)
                .toArray(Method[]::new);
    }

    /**
     * Главная функция сериализации
     *
     * @param javaObject java dto на вход
     * @return .proto Message
     * @throws Exception
     */
    @Override
    public Message serialize(T javaObject) throws Exception {
        if (javaObject == null) {
            throw new IllegalArgumentException("javaObject cannot be null");
        }

        Message.Builder builder = (Message.Builder) builderMethodCache // создание нового экземпляра билдера
                .get(protoClass)
                .invoke(null); // инициализация

        // Итерация по геттерам Java-класса
        for (Method javaGetterMethod : cachedJavaMethods) {
            String fieldName = getFieldNameFromJavaGetter(javaGetterMethod.getName()); // извлечение имени поля из геттера
            Method setter = setters.get(fieldName); // получение метода сеттера из ранее добавленных в мапу полей

            if (setter == null) {
                continue;
            }

            Object value = javaGetterMethod.invoke(javaObject); // получение данных хранимых в поле
            if (value == null) {
                continue;
            }

            try {
                Object adaptedValue = adaptValueToType(value, setter.getParameterTypes()[0]); // адаптируем полученное полученное поле по типу класса
//                log.error("--adaptedValue Has Class = {}", adaptedValue.getClass().getName());
//                log.error("--setterParam[0] = {}, name = {}", setter.getParameterTypes()[0], setter.getParameterTypes()[0].getName());
                setter.invoke(builder, adaptedValue);
            } catch (Exception e) {
                log.error("Error serializing field: {}", fieldName, e);
            }
        }
        return builder.build();
    }

    /**
     * Функция конвертации типов
     *
     * @param value      значение java
     * @param targetType целевой тип конвертации
     * @return
     * @throws Exception
     */
    private Object adaptValueToType(Object value, Class<?> targetType) {
        // Быстрая проверка на null
        if (value == null) {
            return null;
        }

        // Если значение массив - преобразуем в List
        if (value.getClass().isArray()) {
            return handleArray(value);
        }

        // Проверка на коллекцию
        if (value instanceof Collection<?>) {
            return handleCollection(value);
        }

        // Приведение значения к целевому примитивному типу
        if (targetType.isPrimitive()) {
            return convertToPrimitive(value, targetType);
        }

        // Обработка Enum
        if (value.getClass().isEnum()) {
            log.error("Class Have type Enum");
            return adaptEnumToInt(value);
        }

        // Обработка Timestamp
        if (/*targetType == LocalDateTime.class && */value instanceof Timestamp) {
            log.error("Class Have type Timestamp");
            return convertTimestampToLocalDateTime((Timestamp) value);
        }

        // При сериализации
        if (value instanceof LocalDateTime) {
            return convertLocalDateTimeToTimestamp((LocalDateTime) value).toBuilder();
        }

        // Обработка String
        if (targetType == String.class && !(value instanceof String)) {
            return value.toString();
        }

        // если целевой тип - Builder
        if (Message.Builder.class.isAssignableFrom(targetType)) {
            try {
                if (value instanceof Message) {
                    return ((Message) value).toBuilder();
                }
                if (serializerRegistry != null) {
                    @SuppressWarnings("unchecked")
                    ProtobufSerializer<Object> nestedSerializer = (ProtobufSerializer<Object>) serializerRegistry.getSerializer(value.getClass());
                    if (nestedSerializer == null) {
                        throw new RuntimeException("No serializer found for nested field " + value.getClass());
                    }
                    return nestedSerializer.serialize(value).toBuilder();
                }
            } catch (Exception e) {
                log.error("Error during type conversion from {} to {}: {}", value.getClass().getName(), targetType.getName(), e.getMessage());
                throw new RuntimeException("Type conversion failed", e);
            }
        }

        // проверка на совместимость типов // работает с массивами примитивных типов
        return value; // Типы уже совместимы
    }

    private int adaptEnumToInt(Object value) {
        log.error("Value = {}", value.getClass());
        log.error("((Enum<?>) value).ordinal() = {}", ((Enum<?>) value).ordinal());
        if (value instanceof Enum) {
            return ((Enum<?>) value).ordinal();
        }
        throw new IllegalArgumentException("Cannot convert value to enum ordinal: " + value);
    }

    /**
     * Метод для обработки перечисления
     * @param protoValue - тип proto
     * @param targetType - целевой тип
     * @return
     */
//    private Object adaptEnumValue(Object protoValue, Class<?> targetType) {
//        log.error("ENUM: protoValue - {}, targetType - {}", protoValue, targetType);
//        if (protoValue instanceof Enum<?> protoEnum) {
//            // Если прото-enum, конвертируем в Java enum
//            return Enum.valueOf((Class<? extends Enum>) targetType, protoEnum.name());
//        } else if (protoValue instanceof Integer protoEnumValue) {
//            // Если передано числовое значение enum
//            Enum<?>[] enumConstants = (Enum<?>[]) targetType.getEnumConstants();
//            for (Enum<?> enumConstant : enumConstants) {
//                if (enumConstant.ordinal() == protoEnumValue) {
//                    return enumConstant;
//                }
//            }
//            throw new IllegalArgumentException("No enum constant " + targetType.getCanonicalName() + " with value " + protoEnumValue);
//        }
//        throw new IllegalArgumentException("Cannot convert " + protoValue.getClass() + " to enum " + targetType);
//    }

    /**
     * Метод для обработки временной метки
     *
     * @param timestamp
     * @return
     */
    private Object convertTimestampToLocalDateTime(Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }
        return LocalDateTime.ofInstant(
                Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos()),
                ZoneOffset.UTC
        );
    }

    /**
     * Метод для обработки LocalDateTime
     *
     * @param localDateTime
     * @return
     */
    private Timestamp convertLocalDateTimeToTimestamp(LocalDateTime localDateTime) {
        if (localDateTime == null) {
            return null;
        }
        Instant instant = localDateTime.toInstant(ZoneOffset.UTC);
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    /**
     * Метод для обработки массивов данных
     *
     * @param value массив элементов
     * @return ArrayList<?>
     */
    private Object handleArray(Object value) {
        if (value.getClass().getComponentType().isPrimitive()) {
            // Обработка массивов примитивных типов
            if (value instanceof int[]) {
                return Arrays.asList(ArrayUtils.toObject((int[]) value));
            } else if (value instanceof long[]) {
                return Arrays.asList(ArrayUtils.toObject((long[]) value));
            } else if (value instanceof float[]) {
                return Arrays.asList(ArrayUtils.toObject((float[]) value));
            } else if (value instanceof double[]) {
                return Arrays.asList(ArrayUtils.toObject((double[]) value));
            } else if (value instanceof boolean[]) {
                return Arrays.asList(ArrayUtils.toObject((boolean[]) value));
            }
        } else if (value instanceof String[]) {
            // Обработка массивов строк
            return Arrays.asList((String[]) value);
        } else {
            Object[] objectArray = (Object[]) value; // Преобразуем массив объектов в Object[]
            return handleCollection(Arrays.asList(objectArray)); // Передаём в handleCollection как List
        }
        throw new RuntimeException(
                String.format("Error handling array: data = (%s), type(%s)", value, value.getClass())
        );
    }

    /**
     * Метод обработки коллекций данных
     *
     * @param value коллекция элементов
     * @return List<?>
     * @throws Exception
     */
    // принимает в себя коллекцию List чего-либо
    private Object handleCollection(Object value) {
        Collection<?> collection = (Collection<?>) value;
        if (collection.isEmpty() || isPrimitiveOrWrapper(collection.iterator().next().getClass())) {
            return value;
        }

        // Если коллекция сложных объектов (не примитивов)
        if (!collection.isEmpty() && !(collection.iterator().next() instanceof Message)) {
            List<Object> adaptedList = new ArrayList<>();
            for (Object item : collection) {
                // Пытаемся сериализовать каждый элемент коллекции
                if (serializerRegistry != null) {
                    @SuppressWarnings("unchecked")
                    ProtobufSerializer<Object> nestedSerializer = (ProtobufSerializer<Object>) serializerRegistry.getSerializer(item.getClass());
                    if (nestedSerializer != null) {
                        try {
                            // Сериализуем каждый элемент
                            Message serializedItem = nestedSerializer.serialize(item);
                            adaptedList.add(serializedItem);
                        } catch (Exception e) {
                            log.error("Error serializing nested item", e);
                            throw new RuntimeException("Failed to serialize nested item", e);
                        }
                    } else {
                        log.warn("No serializer found for type: {}", item.getClass());
                        throw new RuntimeException("No serializer found for nested type: " + item.getClass());
                    }
                }
            }
            return adaptedList;
        }
        return value; // Если коллекция уже содержит Message
    }

    private Object adaptSingleItem(Object item, Class<?> targetType) {
        if (Message.Builder.class.isAssignableFrom(targetType)) {
            try {
                if (item instanceof Message) {
                    return ((Message) item).toBuilder();
                }
                if (serializerRegistry != null) {
                    @SuppressWarnings("unchecked")
                    ProtobufSerializer<Object> nestedSerializer =
                            (ProtobufSerializer<Object>) serializerRegistry.getSerializer(item.getClass());
                    if (nestedSerializer == null) {
                        throw new RuntimeException("No serializer found for nested field " + item.getClass());
                    }
                    return nestedSerializer.serialize(item).toBuilder();
                }
            } catch (Exception e) {
                throw new RuntimeException("Type conversion failed", e);
            }
        }
        // Для примитивных типов и String
        return adaptValueToType(item, targetType);
    }

    /**
     * Функция конвертации к примитивному типу
     *
     * @param value         значение, приводимое к примитиву
     * @param primitiveType тип примитива
     * @return
     */
    private Object convertToPrimitive(Object value, Class<?> primitiveType) {
//        log.error("ValueClass = {}, {}", value.getClass(), primitiveType);
        // в protobuf поля для enum оканчивающиеся на Value имеют параметр int
        if (value instanceof Enum) {
            return ((Enum<?>) value).ordinal();
        }
        if (primitiveType == float.class) {
            if (value instanceof Number) {
                return ((Number) value).floatValue();
            }
        } else if (primitiveType == int.class) {
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
        } else if (primitiveType == long.class) {
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
        } else if (primitiveType == double.class) {
            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            }
        } else if (primitiveType == boolean.class && value instanceof Boolean) {
            return value;
        }

        throw new IllegalArgumentException(String.format("Cannot convert types: %s -> %s", value.getClass().getName(), primitiveType.getName()));
    }

    /**
     * Функция проверки, что тип является примитивным или обёрткой
     *
     * @param type тип примитива или обёртки
     * @return
     */
    private boolean isPrimitiveOrWrapper(Class<?> type) {
        return type.isPrimitive() ||
                type == Boolean.class ||
                type == Integer.class ||
                type == Long.class ||
                type == Float.class ||
                type == Double.class ||
                type == Short.class ||
                type == Byte.class ||
                type == Character.class ||
                type == String.class;
    }

    /**
     * Функция проверки, что метод является геттером
     *
     * @param javaMethod метод класса
     * @return
     */
    private boolean isGetter(Method javaMethod) {
        String name = javaMethod.getName();
        return (name.startsWith("get") || name.startsWith("is"))
                && javaMethod.getParameterCount() == 0;
    }

    // получение имени поля // optimized

    /**
     * Функция получения имени поля из метода-геттера
     *
     * @param javaMethodName метод класса
     * @return String
     */
    private String getFieldNameFromJavaGetter(String javaMethodName) {
        int prefixLength;
        if (javaMethodName.startsWith("get")) {
            prefixLength = 3;
        } else if (javaMethodName.startsWith("is")) {
            prefixLength = 2;
        } else {
            return null;
        }

        if (javaMethodName.length() <= prefixLength) {
            return null;
        }
        // StringBuilder для оптимизации создания строки
        return new StringBuilder()
                .append(Character.toLowerCase(javaMethodName.charAt(prefixLength)))
                .append(javaMethodName.substring(prefixLength + 1))
                .toString();
    }

    // требует точного соответствия названий полей Java класса и в файле .proto

    /**
     * Функция создания мапы состоящей из методов-сеттеров для сгенерированного класса из .proto
     *
     * @param protoBuilderClass передаваемый класс, сгенерированный изщ .proto
     * @return
     */
    private Map<String, Method> buildSetterMap(Class<? extends Message.Builder> protoBuilderClass) {
        Map<String, Method> setterMap = new HashMap<>();

        for (Method method : protoBuilderClass.getDeclaredMethods()) {
            if (method.getName().startsWith("addAll") && method.getParameterCount() == 1) {
                // пропускаем не Builder методы
                if (Message.class.isAssignableFrom(method.getParameterTypes()[0])) {
                    continue;
                }
                // StringBuilder для оптимизации
                String fieldName = new StringBuilder()
                        .append(Character.toLowerCase(method.getName().charAt(6)))
                        .append(method.getName().substring(7))
                        .toString();

                setterMap.put(fieldName, method);
            }
            if (method.getName().startsWith("set") && method.getParameterCount() == 1) {
                // пропускаем не Builder методы
                if (Message.class.isAssignableFrom(method.getParameterTypes()[0])) {
                    continue;
                }
                if (method.getParameterTypes()[0].isEnum()) {
                    continue;
                }

                // StringBuilder для оптимизации
                String fieldName = new StringBuilder()
                        .append(Character.toLowerCase(method.getName().charAt(3)))
                        .append(method.getName().substring(4))
                        .toString();

                if (fieldName.endsWith("Value")) {
                    fieldName = fieldName.substring(0, fieldName.length() - "Value".length());
                }

                setterMap.put(fieldName, method);
            }
        }
//        setterMap.forEach((k, v) -> log.info("SER: KEY = ({}), VALUE = ({}), PARAMETER TYPES = ({})", k, v.getName(), v.getParameterTypes()[0].getName()));
        return setterMap;
    }
}