package com.test.deserializer;

import com.google.protobuf.*;
import com.google.protobuf.ProtocolStringList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.Enum;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Класс, в котором представлена логика десериализации
 *
 * @param <T>
 */
public class UniversalProtobufDeserializer<T> implements ProtobufDeserializer<T> {
    /**
     * Java-класс, в который будет происходить десериализация
     */
    private final Class<T> javaClass;

    /**
     * Мапа, где ключами являются имена полей, а значениями — методы setFieldName java класса
     */
    private final Map<String, Method> setters;

    /**
     * Реестр десериализаторов для обработки вложенных объектов
     */
    private final DeserializerRegistry deserializerRegistry;

    /**
     * Для отображения логов
     */
    private final Logger log = LoggerFactory.getLogger(UniversalProtobufDeserializer.class);

    /**
     * Кэширование важных методов при создании объекта
     */
    private final transient Method[] cachedProtoMethods;

    /**
     * @param javaClass            передаваемый Java класс
     * @param protoClass           передаваемый proto класс
     * @param deserializerRegistry передаваемый реестр десериализаторов
     */
    public UniversalProtobufDeserializer(
            Class<T> javaClass,
            Class<? extends Message> protoClass,
            DeserializerRegistry deserializerRegistry
    ) {
        this.javaClass = javaClass; // получение Java класса
        this.deserializerRegistry = deserializerRegistry; // создание переменной для доступа к реестру десериалайзеров
        this.setters = buildSetterMap(javaClass); // Составляем мапу сеттеров для Java-класса

        // кэширование методов заранее
        this.cachedProtoMethods = preCacheProtoMethods((Class<T>) protoClass);
    }

    /**
     * Метод получения отфильтрованных методов класса
     * @param clazz - proto класс
     * @return
     */
    private Method[] preCacheProtoMethods(Class<T> clazz) {
        return Arrays.stream(clazz.getDeclaredMethods())
                .filter(method ->
                        // отбрасываем set методы из proto-класса
                        // И, методы у которых возвращаемый тип ProtocolStringList (он дублируется в методах getList для List<String>)
                        isGetter(method) && !ProtocolStringList.class.isAssignableFrom(method.getReturnType())
                )
                .toArray(Method[]::new);
    }

    /**
     * Главная функция десериализации
     *
     * @param protoMessage proto сообщение на вход
     * @return JavaObject
     * @throws Exception
     */
    @Override
    public T deserialize(Message protoMessage) throws Exception {
        if (protoMessage == null) {
            throw new IllegalArgumentException("protoMessage cannot be null");
        }

        T javaObject = javaClass.getDeclaredConstructor().newInstance(); // создание конструктора для нового класса в процессе десереиализации

        // Перебираем поля класса Protobuf-сообщения отобранные заранее
        for (Method protoMethod : cachedProtoMethods) {
            String fieldName = getFieldNameFromGetter(protoMethod.getName());
            Method setter = setters.get(fieldName); // получение метода по ключу

            if (setter == null || fieldName == null) {
                continue;
            }

            try {
                if (setters.containsKey(fieldName)) { // если в мапе сеттеров есть этот ключ -> идём дальше
                    Object protoValue = protoMethod.invoke(protoMessage);
                    if (protoValue == null) {
                        continue;
                    }
                    Object javaValue = adaptValueToType(protoValue, setter.getParameterTypes()[0]); // адаптация proto-объекта по типу класса
                    setter.invoke(javaObject, javaValue); // сетим полученное значение к заполняемому объекту
                }
            } catch (Exception e) {
                log.error("Error deserializing field: {}", fieldName, e);
            }
        }
        return javaObject;
    }

    /**
     * Функция конвертации типов
     *
     * @param protoValue - значение .proto
     * @param targetType - целевой тип конвертации
     * @return
     * @throws Exception
     */
    private Object adaptValueToType(Object protoValue, Class<?> targetType) throws Exception {
        // Если целевой тип массив
        if (targetType.isArray()) {
            if (protoValue instanceof List<?>) {
                return handleArray(protoValue, targetType);
            }
        }

        // Обработка коллекций
        if (Collection.class.isAssignableFrom(targetType)) {
            if (protoValue instanceof Collection) {
                return handleCollection(protoValue);
            }
        }

        // если типы совпадают
        if (targetType.isInstance(protoValue)) {
            return protoValue;
        }

        // Обработка примитивных типов
        if (targetType.isPrimitive()) {
            return convertToPrimitive(protoValue, targetType);
        }

        // Обработка Enum
        if (targetType.isEnum()) {
            return adaptEnumValue(protoValue, targetType);
        }

        // Обработка Timestamp/LocalDateTime
        if (targetType == LocalDateTime.class && protoValue instanceof Timestamp) {
            return convertTimestampToLocalDateTime((Timestamp) protoValue);
        }

        // Обработка UUID
        if (targetType == UUID.class && protoValue instanceof String) {
            try {
                return UUID.fromString((String) protoValue);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid UUID string: " + protoValue, e);
            }
        }

        // Обработка String
        if (targetType == String.class && !(protoValue instanceof String)) {
            log.info("STUCK HERE!");
            return protoValue.toString();
        }

        // обработка вложенных типов
        try {
            if (Message.class.isAssignableFrom(protoValue.getClass())) {
                ProtobufDeserializer<?> nestedDeserializer = deserializerRegistry.getDeserializer(targetType);
                if (nestedDeserializer == null) {
                    throw new RuntimeException("No deserializer found for nested field " + targetType.getName());
                }
                return nestedDeserializer.deserialize((Message) protoValue);
            }
        } catch (Exception e) {
            log.error("Error during nested object deserialization", e);
            throw new RuntimeException("Failed to deserialize nested object", e);
        }
        throw new IllegalArgumentException(
                String.format("Unsupported type conversion: %s -> %s", protoValue.getClass().getName(), targetType.getName())
        );
    }

    /**
     * Метод обработки enum'ов
     *
     * @param protoValue
     * @param targetType
     * @return
     */
    private Object adaptEnumValue(Object protoValue, Class<?> targetType) {
        log.warn("ProtoValue = {} || ProtoType = {} || targetType = {}", protoValue, protoValue.getClass(), targetType.getName());
        if (targetType.isEnum()) {
            try {
                // Если значение — число (ordinal)
                if (protoValue instanceof Integer) {
                    int ordinal = (Integer) protoValue;
                    Object[] enumConstants = targetType.getEnumConstants();
                    if (ordinal >= 0 && ordinal < enumConstants.length) {
                        return enumConstants[ordinal];
                    } else {
                        throw new IllegalArgumentException(
                                String.format("Invalid ordinal value %d for enum type %s", ordinal, targetType.getName())
                        );
                    }
                }
                // Если значение — строка (имя константы)
                if (protoValue instanceof String) {
                    String enumName = (String) protoValue;
                    Object x = Enum.valueOf((Class<Enum>) targetType, enumName);
                    log.error("x = {}", x);
                    return x;
                }

                // Если значение — Protobuf-перечисление
                if (protoValue.getClass().isEnum()) {
                    // Получаем числовое значение (number) из Protobuf-перечисления
                    try {
                        Method numberMethod = protoValue.getClass().getMethod("getNumber");
                        int number = (int) numberMethod.invoke(protoValue);

                        // Преобразуем число в Java-перечисление
                        Object[] enumConstants = targetType.getEnumConstants();
                        if (number >= 0 && number < enumConstants.length) {
                            return enumConstants[number];
                        } else {
                            throw new IllegalArgumentException(
                                    String.format("Invalid number value %d for enum type %s", number, targetType.getName())
                            );
                        }
                    } catch (Exception e) {
                        throw new IllegalArgumentException(
                                String.format("Failed to extract number from Protobuf enum %s", protoValue.getClass().getName()), e
                        );
                    }
                }

            } catch (Exception e) {
                throw new IllegalArgumentException(
                        String.format("Cannot convert value %s to enum type %s", protoValue, targetType.getName()), e
                );
            }
        }
        throw new IllegalArgumentException(
                String.format("Cannot convert value %s to enum type %s", protoValue, targetType.getName())
        );
    }

    /**
     * Метод преобразования timestamp в LocalDateTime
     *
     * @param timestamp
     * @return
     */
    private LocalDateTime convertTimestampToLocalDateTime(Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }
        Instant instant = Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * Метод для обработки массивов данных
     *
     * @param protoValue - данные в .proto формате
     * @param targetType - целевой тип
     * @return
     * @throws Exception
     */
    private Object handleArray(Object protoValue, Class<?> targetType) throws Exception {
        List<?> list = (List<?>) protoValue;
        // обработка пустого массива
        if (list.isEmpty()) {
            return Array.newInstance(targetType.getComponentType(), 0);
        }

        Object array = Array.newInstance(targetType.getComponentType(), list.size());
        Class<?> componentType = targetType.getComponentType(); // Тип элементов массива

        if (componentType.isPrimitive()) {
            // Преобразование в массив примитивов
            Object primitiveArray = Array.newInstance(componentType, list.size());
            for (int i = 0; i < list.size(); i++) {
                Object convertedValue = convertToPrimitive(list.get(i), componentType);
                Array.set(primitiveArray, i, convertedValue);
            }
            return primitiveArray;
        } else {
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                // Если это вложенные Protobuf-объекты, выполняем десериализацию
                if (Message.class.isAssignableFrom(item.getClass())) { // todo много раз повторяется
                    ProtobufDeserializer<?> nestedDeserializer = deserializerRegistry.getDeserializer(componentType);
                    if (nestedDeserializer == null) {
                        throw new RuntimeException("No deserializer found for nested field: " + componentType.getName());
                    }
                    Object adaptedItem = nestedDeserializer.deserialize((Message) item);
                    Array.set(array, i, adaptedItem);
                } else if (componentType.isInstance(item)) {
                    // Прямое присваивание, если тип уже совпадает
                    Array.set(array, i, item);
                } else {
                    throw new IllegalArgumentException(
                            String.format("Cannot adapt item of type %s -> %s", item.getClass().getName(), componentType.getName())
                    );
                }
            }
            return array;
        }
    }

    /**
     * метод обработки коллекций данных
     *
     * @param protoValue
     * @return
     * @throws Exception
     */
    private Object handleCollection(Object protoValue) throws Exception {
        // Создаем список для адаптированных элементов
        List<Object> adaptedList = new ArrayList<>();

        // Если коллекция примитивных элементов, просто копируем
        if (isPrimitiveOrWrapper(((Collection<?>) protoValue).iterator().next().getClass())) {
            return protoValue;
        }

        // Определяем тип элементов в коллекции
        Class<?> elementType;
        for (Object item : (Collection<?>) protoValue) {
            // Если тип элементов не определен, пробуем определить по первому элементу

            elementType = item.getClass();

            // Обработка сложных объектов
            if (Message.class.isAssignableFrom(elementType)) {
                @SuppressWarnings("unchecked")
                Class<?> specifiedJavaClass = deserializerRegistry.getJavaClassForProto(
                        (Class<? extends Message>) elementType
                );

                // Fallback, если не нашли явно указанный класс
                if (specifiedJavaClass == null) {
                    specifiedJavaClass = item.getClass();
                    log.warn("No specific Java class found for Proto class {}. Using default.", item.getClass().getSimpleName());
                }

                // Пытаемся найти десериализатор для конкретного типа
                ProtobufDeserializer<?> nestedDeserializer = deserializerRegistry.getDeserializer(specifiedJavaClass);

                if (nestedDeserializer == null) {
                    throw new RuntimeException(
                            "No deserializer found for nested field: (" + elementType.getName() + ") or item type (" + item.getClass().getName() + ")"
                    );
                }
                adaptedList.add(nestedDeserializer.deserialize((Message) item));
            }
            // Прямое преобразование для совместимых типов
            else {
                adaptedList.add(item);
            }
        }
        return adaptedList;
    }

    /**
     * Метод для проверки примитивных оберток
     *
     * @param type
     * @return
     */
    private boolean isPrimitiveWrapper(Class<?> type) {
        return type == Boolean.class ||
                type == Integer.class ||
                type == Long.class ||
                type == Float.class ||
                type == Double.class ||
                type == Short.class ||
                type == Byte.class ||
                type == Character.class;
    }

    /**
     * Функция проверки примитивный тип или обёртка
     *
     * @param type
     * @return
     */
    private boolean isPrimitiveOrWrapper(Class<?> type) {
        return type.isPrimitive() || isPrimitiveWrapper(type) || type == String.class;
    }

    /**
     * Функция конвертации к примитивному типу
     *
     * @param value
     * @param primitiveType
     * @return
     */
    private Object convertToPrimitive(Object value, Class<?> primitiveType) {
        if (primitiveType == float.class) {
            if (value instanceof Float) return ((Float) value).floatValue();
            if (value instanceof Double) return ((Double) value).floatValue();
        }

        if (primitiveType == int.class) {
            if (value instanceof Integer) return ((Integer) value).intValue();
            if (value instanceof Long) return ((Long) value).intValue();
        }

        if (primitiveType == long.class) {
            if (value instanceof Long) return ((Long) value).longValue();
            if (value instanceof Integer) return ((Integer) value).longValue();
        }

        if (primitiveType == boolean.class && value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }

        if (primitiveType == double.class) {
            if (value instanceof Double) return ((Double) value).doubleValue();
            if (value instanceof Float) return ((Float) value).doubleValue();
        }

        throw new IllegalArgumentException(
                String.format("Unsupported primitive conversion: %s -> %s", value.getClass().getName(), primitiveType.getName())
        );
    }

    /**
     * Функция проверки, что метод является геттером
     *
     * @param method
     * @return
     */
    private boolean isGetter(Method method) {
        String name = method.getName();
        return (name.startsWith("get") || name.startsWith("is"))
                && method.getParameterCount() == 0;
    }

    // получение имени поля // optimized

    /**
     * Функция получения имени поля из метода-геттера
     *
     * @param protoMethodName
     * @return
     */
    private String getFieldNameFromGetter(String protoMethodName) {
        // все сгенерированные из .proto java-геттеры дял переменных начинаются с "get"
        if (protoMethodName.startsWith("get") && protoMethodName.length() > 3) {

            String fieldName = protoMethodName.substring(3); // получаем часть имени метода после "get"
            int methodNameLength = fieldName.length();
            boolean isList = methodNameLength > 4 && fieldName.endsWith("List");

            if (isList || methodNameLength > 0) { // Убедимся, что fieldName не пустая
                char firstChar = Character.toLowerCase(fieldName.charAt(0));
                int endIndex = isList ? methodNameLength - 4 : methodNameLength;
                return new StringBuilder(endIndex)
                        .append(firstChar)
                        .append(fieldName, 1, endIndex)
                        .toString();
            }
        }
        return null;
    }

    // требует точного соответствия названий полей Java класса и в файле .proto

    /**
     * Функция создания мапы, сосотоящей из методов-сеттеров для Java класса
     *
     * @param javaClazz передаваемый Java класс
     * @return
     */
    private Map<String, Method> buildSetterMap(Class<T> javaClazz) {
        Map<String, Method> map = new HashMap<>();

        for (Method method : javaClazz.getDeclaredMethods()) {
            if (!method.getName().startsWith("set") || method.getParameterCount() != 1) {
                continue;
            }
            String fieldName = Character.toLowerCase(method.getName().charAt(3)) + method.getName().substring(4); // todo оптимизировать по памяти
            map.put(fieldName, method);
        }
//        map.forEach((k, v) -> log.info("DE_SER: KEY = ({}), VALUE = ({}), PARAMETER TYPES = ({})", k, v.getName(), v.getParameterTypes()[0].getName()));

        return map;
    }
}