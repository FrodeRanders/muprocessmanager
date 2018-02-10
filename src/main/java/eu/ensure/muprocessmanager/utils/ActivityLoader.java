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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Dynamically loads objects.
 * <p>
 * Used by the micro-process manager in order to instantiate compensation activities
 * from persistent store.
 */
public class ActivityLoader<C> {
    private static final Logger log = LogManager.getLogger(ActivityLoader.class);

    private String description;

    /**
     * Constructor.
     * <p>
     * The <i>description</i> parameter is used when producing
     * log output and has no other function. It makes the log
     * a whole lot easier to read - do use it!
     */
    public ActivityLoader(String description) {
        this.description = description;
    }

    /**
     * Dynamically loads the named class (fully qualified classname) and
     * creates an instance from it.
     */
    public C load(String className) throws ClassNotFoundException {

        Class clazz = createClass(className);
        return createObject(className, clazz);
    }

    /**
     * Dynamically loads the named class (fully qualified classname).
     */
    public Class createClass(String className) throws ClassNotFoundException {
        Class clazz;
        try {
            clazz = Class.forName(className);
            return clazz;

        } catch (ExceptionInInitializerError eiie) {
            String info = "Could not load the " + description + " object: " + className
                    + ". Could not initialize static object in server: ";
            info += eiie.getMessage();
            throw new ClassNotFoundException(info, eiie);

        } catch (LinkageError le) {
            String info = "Could not load the " + description + " object: " + className
                    + ". This object is depending on a class that has been changed after compilation ";
            info += "or a class that was not found: ";
            info += le.getMessage();
            throw new ClassNotFoundException(info, le);

        } catch (ClassNotFoundException cnfe) {
            String info = "Could not find the " + description + " object: " + className + ": ";
            info += cnfe.getMessage();
            throw new ClassNotFoundException(info, cnfe);
        }
    }

    /**
     * Creates an instance from a Class.
     */
    public C createObject(String className, Class clazz) throws ClassNotFoundException {
        try {
            @SuppressWarnings("unchecked")
            C object = (C) clazz.newInstance();
            return object;

        } catch (InstantiationException ie) {
            String info = "Could not create " + description + " object: " + className
                    + ". Could not access object constructor: ";
            info += ie.getMessage();
            throw new ClassNotFoundException(info, ie);

        } catch (IllegalAccessException iae) {
            String info = "Could not create " + description + " object: " + className
                    + ". Could not instantiate object. Does the object classname refer to an abstract class, "
                    + "an interface or the like?: ";
            info += iae.getMessage();
            throw new ClassNotFoundException(info, iae);

        } catch (ClassCastException cce) {
            String info = "Could not create " + description + " object: " + className
                    + ". The specified object classname does not refer to the proper type: ";
            info += cce.getMessage();
            throw new ClassNotFoundException(info, cce);
        }
    }

    /**
     * Creates a method for a class
     */
    public Method createMethod(C object, String methodName, Class[] parameterTypes) throws NoSuchMethodException {
        Class clazz = object.getClass();

        try {
            @SuppressWarnings("unchecked")
            Method method = clazz.getMethod(methodName, parameterTypes);
            return method;

        } catch (NoSuchMethodException nsme) {
            String info = "The specified class " + clazz.getName();
            info += " does not have a method \"" + methodName + "\" as expected: ";
            info += nsme.getMessage();
            throw new NoSuchMethodException(info);
        }
    }

    /**
     * Calls a method on an object. Will not dynamically determine parameter types.
     * <p>
     * May be used to call methods with polymorphous parameters, i.e. methods
     * taking parameters such as List, Map, etc (that are abstract).
     * <p>
     * Example:
     * <pre>
     * // Method name
     * String methodName = "method";
     *
     * // Parameter types and values
     * Object[] parameters = { new Vector&lt;String&gt;() };
     * Class[] types = { List.class };
     *
     * // Method call
     * callMethodOn(object, methodName, parameters, types);
     * </pre>
     * <p>
     * @param object - any object
     * @param methodName - name of (public) method
     * @param parameters - an array of values matching the parameterTypes array
     * @param parameterTypes - an array of parameter types (Class) matching parameters array
     * @throws ClassNotFoundException - if method is not found, method is not public, parameters does not match, etc.
     */
    public void callMethodOn(C object, String methodName, Object[] parameters, Class[] parameterTypes)
            throws Throwable {

        Class clazz = object.getClass();
        try {
            @SuppressWarnings("unchecked")
            Method method = clazz.getMethod(methodName, parameterTypes);
            method.invoke(object, parameters);

        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (null != cause) {
                throw cause;
            }
            String info = "Could not invoke \"" + methodName + "\" on \"" + clazz.getName() + "\"";
            info += " as expected: ";
            info += ite.getMessage();
            throw new ClassNotFoundException(info);

        } catch (IllegalAccessException iae) {
            String info = "The specified class " + clazz.getName();
            info += " does not have a public method \"" + methodName + "\" as expected: ";
            info += iae.getMessage();
            throw new ClassNotFoundException(info);

        } catch (NoSuchMethodException nsme) {
            String info = "The specified class " + clazz.getName();
            info += " does not have a method \"" + methodName + "\" as expected: ";
            info += nsme.getMessage();
            throw new ClassNotFoundException(info);
        }
    }

    /**
     * Calls a method on an object. Beware that this version of callMethodOn() will
     * assume the exact parameter types in the designated method as has the parameters
     * to this call.
     * <p>
     * An effect of this is that if you call a method assuming a type T with an
     * object of the derived type D, the method call will fail. In this case (when
     * using polymorphous parameters) you should use the version also taking an
     * array of parameter types (see above).
     * <p>
     * Example:
     * <pre>
     * // Method name
     * String methodName = "method";
     *
     * // Parameter values
     * Object[] parameters = { param };
     *
     * // Method call
     * callMethodOn(object, methodName, parameters);
     * </pre>
     * <p>
     * @param object - any object
     * @param methodName - name of (public) method
     * @param parameters - an array of values matching the parameterTypes array
     * @throws ClassNotFoundException - if method is not found, method is not public, parameters does not match, etc.
     */
    public void callMethodOn(C object, String methodName, Object[] parameters)
            throws Throwable {

        // Dynamically determine parameter types
        Class[] parameterTypes = new Class[parameters.length];
        for (int i=0; i < parameters.length; i++) {
            parameterTypes[i] = parameters[i].getClass();
        }

        callMethodOn(object, methodName, parameters, parameterTypes);
    }
}
