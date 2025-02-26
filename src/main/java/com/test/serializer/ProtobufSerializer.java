package com.test.serializer;

import com.google.protobuf.Message;

public interface ProtobufSerializer<T> {
    Message serialize(T javaObject) throws Exception;
}
