/**
 * Copyright (c) 2017, Stephan Saalfeld
 * All rights reserved.
 * <p>
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * <p>
 * 1. Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * <p>
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package org.janelia.saalfeldlab.n5.zarr.serialization;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import org.scijava.annotations.Index;
import org.scijava.annotations.IndexItem;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map.Entry;

/**
 * T adapter, auto-discovers annotated T implementations in the classpath.
 *
 * @author Caleb Hulbert
 */
public class ZarrNameConfigAdapter<T extends ZarrNameConfig> implements JsonDeserializer<T>, JsonSerializer<T> {

	private static HashMap<Class<? extends ZarrNameConfig>, ZarrNameConfigAdapter<? extends ZarrNameConfig>> adapters = new HashMap<>();

	private static <V extends ZarrNameConfig> void registerAdapter(Class<V> cls) {

		adapters.put(cls, new ZarrNameConfigAdapter<V>());
		update(adapters.get(cls));
	}

	private final HashMap<String, Constructor<? extends T>> chunkGridConstructors = new HashMap<>();
	private final HashMap<String, HashMap<String, String>> chunkGridParameterNames = new HashMap<>();
	private final HashMap<String, HashMap<String, Class<?>>> chunkGridParameters = new HashMap<>();

	private ZarrNameConfigAdapter() {

	}

	private static ArrayList<Field> getDeclaredFields(Class<?> clazz) {

		final ArrayList<Field> fields = new ArrayList<>();
		fields.addAll(Arrays.asList(clazz.getDeclaredFields()));
		for (clazz = clazz.getSuperclass(); clazz != null; clazz = clazz.getSuperclass())
			fields.addAll(Arrays.asList(clazz.getDeclaredFields()));
		return fields;
	}

	@SuppressWarnings("unchecked")
	public static synchronized <T extends ZarrNameConfig> void update(final ZarrNameConfigAdapter<T> adapter) {

		final ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
		final Index<T.Name> annotationIndex = Index.load(T.Name.class, classLoader);
		for (final IndexItem<T.Name> item : annotationIndex) {
			Class<T> clazz;
			try {
				clazz = (Class<T>)Class.forName(item.className());
				final String name = clazz.getAnnotation(T.Name.class).value();
				final String prefix = clazz.getAnnotation(ZarrNameConfig.Prefix.class).value();
				final String type = prefix + "." + name;

				final Constructor<T> constructor = clazz.getDeclaredConstructor();

				final HashMap<String, Class<?>> parameters = new HashMap<>();
				final HashMap<String, String> parameterNames = new HashMap<>();
				final ArrayList<Field> fields = getDeclaredFields(clazz);
				for (final Field field : fields) {
					final T.Parameter parameter = field.getAnnotation(T.Parameter.class);
					if (parameter != null) {

						final String parameterName;
						if (parameter.value().equals(""))
							parameterName = field.getName();
						else
							parameterName = parameter.value();

						parameterNames.put(field.getName(), parameterName);

						parameters.put(field.getName(), field.getType());
					}
				}

				adapter.chunkGridConstructors.put(type, constructor);
				adapter.chunkGridParameters.put(type, parameters);
				adapter.chunkGridParameterNames.put(type, parameterNames);
			} catch (final ClassNotFoundException | NoSuchMethodException | ClassCastException
						   | UnsatisfiedLinkError e) {
				System.err.println("T '" + item.className() + "' could not be registered");
			}
		}
	}

	@Override
	public JsonElement serialize(
			final T object,
			final Type typeOfSrc,
			final JsonSerializationContext context) {

		final String type = object.getType();
		final Class<T> clazz = (Class<T>)object.getClass();

		final JsonObject chunkGridJson = new JsonObject();
		chunkGridJson.addProperty("name", type);
		final JsonObject configuration = new JsonObject();
		chunkGridJson.add("configuration", configuration);

		final HashMap<String, Class<?>> parameterTypes = chunkGridParameters.get(type);
		final HashMap<String, String> parameterNames = chunkGridParameterNames.get(type);
		try {
			for (final Entry<String, Class<?>> parameterType : parameterTypes.entrySet()) {
				final String fieldName = parameterType.getKey();
				final Field field = clazz.getDeclaredField(fieldName);
				final boolean isAccessible = field.isAccessible();
				field.setAccessible(true);
				final Object value = field.get(object);
				field.setAccessible(isAccessible);
				configuration.add(parameterNames.get(fieldName), context.serialize(value));
			}
		} catch (NoSuchFieldException | SecurityException | IllegalArgumentException | IllegalAccessException e) {
			e.printStackTrace(System.err);
			return null;
		}

		return chunkGridJson;
	}

	@Override
	public T deserialize(
			final JsonElement json,
			final Type typeOfT,
			final JsonDeserializationContext context) throws JsonParseException {

		String prefix =((Class<T>)typeOfT).getAnnotation(ZarrNameConfig.Prefix.class).value();

		final JsonObject chunkGridJson = json.getAsJsonObject();
		final String name = chunkGridJson.getAsJsonPrimitive("name").getAsString();
		if (name == null)
			return null;
		final JsonObject configuration = chunkGridJson.getAsJsonObject("configuration");
		if (configuration == null)
			return null;

		final String type = prefix + "." + name;

		final Constructor<? extends T> constructor = chunkGridConstructors.get(type);
		constructor.setAccessible(true);
		final T chunkGrid;
		try {
			chunkGrid = constructor.newInstance();
			final HashMap<String, Class<?>> parameterTypes = chunkGridParameters.get(type);
			final HashMap<String, String> parameterNames = chunkGridParameterNames.get(type);
			for (final Entry<String, Class<?>> parameterType : parameterTypes.entrySet()) {
				final String fieldName = parameterType.getKey();
				final String paramName = parameterNames.get(fieldName);
				final JsonElement paramJson = configuration.get(paramName);
				if (paramJson != null) {
					final Object parameter = context.deserialize(paramJson, parameterType.getValue());
					ReflectionUtils.setFieldValue(chunkGrid, fieldName, parameter);
				}
			}
		} catch (InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException
				 | SecurityException | NoSuchFieldException e) {
			e.printStackTrace(System.err);
			return null;
		}

		return chunkGrid;
	}

	public static <T extends ZarrNameConfig> ZarrNameConfigAdapter<T> getJsonAdapter(Class<T> cls) {

		if (adapters.get(cls) == null)
			registerAdapter(cls);
		return (ZarrNameConfigAdapter<T>) adapters.get(cls);
	}
}