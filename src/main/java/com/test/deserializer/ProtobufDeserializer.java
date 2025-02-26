package com.test.deserializer;

import com.google.protobuf.Message;

public interface ProtobufDeserializer<T> {
    T deserialize(Message protoMessage) throws Exception;
}
