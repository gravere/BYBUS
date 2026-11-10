package com.lody.virtual.helper.compat;

import java.lang.reflect.Method;
import java.util.List;

import mirror.android.content.pm.ParceledListSlice;
import mirror.android.content.pm.ParceledListSliceJBMR2;

/**
 * @author Lody
 *
 */
public class ParceledListSliceCompat {

	public static boolean isReturnParceledListSlice(Method method) {
		return method != null && isSliceType(method.getReturnType());
	}

	public static boolean isParceledListSlice(Object obj) {
		return obj != null && isSliceType(obj.getClass());
	}

	private static boolean isSliceType(Class<?> type) {
		return ParceledListSlice.TYPE != null && ParceledListSlice.TYPE.isAssignableFrom(type);
	}

	/** getList is inherited from BaseParceledListSlice on modern Android. */
	public static List<?> getList(Object slice) {
		if (!isParceledListSlice(slice)) {
			throw new IllegalArgumentException("Expected a platform list slice");
		}
		try {
			Object list = slice.getClass().getMethod("getList").invoke(slice);
			if (list instanceof List) return (List<?>) list;
			throw new IllegalStateException("Platform list slice returned no list");
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Unable to read platform list slice", e);
		}
	}

	/** Preserve the exact Binder return type, including Android 17 PackageInfoList. */
	public static Object create(List list, Method method) {
		return createAs(list, method.getReturnType());
	}

	/** Preserve a typed slice when a proxy rewrites an existing argument. */
	public static Object createLike(List list, Object original) {
		return createAs(list, original.getClass());
	}

	private static Object createAs(List list, Class<?> type) {
		if (type.isInstance(list)) return list;
		if (!isSliceType(type)) {
			throw new IllegalArgumentException("Unsupported list result type: " + type.getName());
		}
		if (type == ParceledListSlice.TYPE) {
			Object slice = create(list);
			if (slice == null) throw new IllegalStateException("Unable to construct " + type.getName());
			return slice;
		}
		try {
			return type.getConstructor(List.class).newInstance(list);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Unable to construct " + type.getName(), e);
		}
	}

	public static  Object create(List list) {
		if (ParceledListSliceJBMR2.ctor != null) {
			return ParceledListSliceJBMR2.ctor.newInstance(list);
		} else {
			Object slice = ParceledListSlice.ctor.newInstance();
			for (Object item : list) {
				ParceledListSlice.append.call(slice, item);
			}
			ParceledListSlice.setLastSlice.call(slice, true);
			return slice;
		}
	}

}
