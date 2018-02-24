/*
 * Copyright (C) 2017 Frode Randers
 * All rights reserved
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
 *
 */
package eu.ensure.muprocessmanager.utils;

import java.io.*;

/**
 * Properly deep-clones objects using internal serialization and subsequent deserialization.
 */
public class Cloner {

    private static ByteArrayOutputStream toOutputStream(Object object) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(object);
            oos.flush();
        }
        return baos;
    }

    private static ObjectInputStream toInputStream(ByteArrayOutputStream baos) throws IOException {
        return new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray()));
    }

    private static ObjectInputStream toInputStream(Object object) throws IOException {
        try (ByteArrayOutputStream baos = toOutputStream(object)) {
            return toInputStream(baos);
        }
    }

    /**
     * Makes a 'snapshot' of an object, i.e. makes a deep clone of the object structure
     * to preserve state and protect against later modification.
     * <p>
     * @param original
     * @return
     * @throws IOException
     * @throws ClassNotFoundException
     */
    public static <T> T clone(T original) throws IOException, ClassNotFoundException {
        T object;

        try (ObjectInputStream ois = toInputStream(original)) {
            @SuppressWarnings("unchecked")
            T _object = (T) ois.readObject(); // in order to use this annotation
            object = _object;
        }

        return object;
    }
}
