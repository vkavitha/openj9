/*******************************************************************************
 * Copyright IBM Corp. and others 2023
 *
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License 2.0 which accompanies this
 * distribution and is available at https://www.eclipse.org/legal/epl-2.0/
 * or the Apache License, Version 2.0 which accompanies this distribution and
 * is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * This Source Code may also be made available under the following
 * Secondary Licenses when the conditions for such availability set
 * forth in the Eclipse Public License, v. 2.0 are satisfied: GNU
 * General Public License, version 2 with the GNU Classpath
 * Exception [1] and GNU General Public License, version 2 with the
 * OpenJDK Assembly Exception [2].
 *
 * [1] https://www.gnu.org/software/classpath/license.html
 * [2] https://openjdk.org/legal/assembly-exception.html
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0 OR GPL-2.0-only WITH OpenJDK-assembly-exception-1.0
 *******************************************************************************/
package org.openj9.test.lworld;


import jdk.internal.misc.Unsafe;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.List;
import static org.testng.Assert.*;
import org.testng.annotations.Test;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import static org.openj9.test.lworld.ValueTypeTestClasses.*;
import static org.openj9.test.lworld.ValueTypeTests.*;


@Test(groups = { "level.sanity" })
public class ValueTypeUnsafeTests {
	static Unsafe myUnsafe = Unsafe.getUnsafe();
	static boolean isCompressedRefsEnabled = false;
	static boolean isDualHeaderShapeDisabled = false;
	static boolean isGcPolicyGencon = false;
	static boolean isFlatteningEnabled = false;
	static boolean isArrayFlatteningEnabled = false;

	static ValueTypePoint2D! vtPoint;
	static ValueTypePoint2D![] vtPointAry;
	static ValueInt![] vtIntAry;
	static long vtPointOffsetX;
	static long vtPointOffsetY;
	static long vtLongPointOffsetX;
	static long vtLongPointOffsetY;
	static long vtPointAryOffset0;
	static long vtPointAryOffset1;
	static long intWrapperOffsetVti;
	static long vtIntAryOffset0;
	static long vtIntAryOffset1;

	@BeforeClass
	static public void testSetUp() throws Throwable {
		List<String> arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
		isFlatteningEnabled = arguments.contains("-XX:ValueTypeFlatteningThreshold=99999");
		isArrayFlatteningEnabled = arguments.contains("-XX:+EnableArrayFlattening");
		isCompressedRefsEnabled = arguments.contains("-Xcompressedrefs");
		// If dual header shape is disabled array header will have dataAddr field, 8 bytes in size, regardless of GC policy
		isDualHeaderShapeDisabled = arguments.contains("-XXgc:disableIndexableDualHeaderShape");
		// Dual header shape affects Gencon GC only
		isGcPolicyGencon = arguments.contains("-Xgcpolicy:gencon")
			|| (!arguments.contains("-Xgcpolicy:optthruput")
				&& !arguments.contains("-Xgcpolicy:balanced")
				&& !arguments.contains("-Xgcpolicy:metronome")
				&& !arguments.contains("-Xgcpolicy:optavgpause"));

		vtPointOffsetX = myUnsafe.objectFieldOffset(ValueTypePoint2D.class.getDeclaredField("x"));
		vtPointOffsetY = myUnsafe.objectFieldOffset(ValueTypePoint2D.class.getDeclaredField("y"));
		vtLongPointOffsetX = myUnsafe.objectFieldOffset(ValueTypeLongPoint2D.class.getDeclaredField("x"));
		vtLongPointOffsetY = myUnsafe.objectFieldOffset(ValueTypeLongPoint2D.class.getDeclaredField("y"));
		vtPointAryOffset0 = myUnsafe.arrayBaseOffset(ValueTypePoint2D[].class);
		intWrapperOffsetVti = myUnsafe.objectFieldOffset(IntWrapper.class.getDeclaredField("vti"));
		vtIntAryOffset0 = myUnsafe.arrayBaseOffset(ValueInt[].class);
	}

	// array must not be empty
	static private <T> long arrayElementSize(T[] array) {
		// don't simply use getObjectSize(array[0]) - valueHeaderSize(array[0].getClass()) because the amount of memory used to store an object may be different depending on if it is inside an array or not (for instance, when flattening is disabled)
		return (myUnsafe.getObjectSize(array) - myUnsafe.arrayBaseOffset(array.getClass())) / array.length;
	}

	@BeforeMethod
	static public void setUp() {
		vtPoint = new ValueTypePoint2D(new ValueInt(7), new ValueInt(8));
		vtPointAry = new ValueTypePoint2D![2];
		vtPointAry[0] = new ValueTypePoint2D(new ValueInt(5), new ValueInt(10));
		vtPointAry[1] = new ValueTypePoint2D(new ValueInt(10), new ValueInt(20));
		vtPointAryOffset1 = vtPointAryOffset0 + arrayElementSize(vtPointAry);
		vtIntAry = new ValueInt![2];
		vtIntAry[0] = new ValueInt(1);
		vtIntAry[1] = new ValueInt(2);
		vtIntAryOffset1 = vtIntAryOffset0 + arrayElementSize(vtIntAry);
	}

	public static class CompareAndDoSomethingFunction {
		private Method method;

		public CompareAndDoSomethingFunction(String methodName) throws NoSuchMethodException {
			this.method = myUnsafe.getClass().getMethod(
				methodName,
				new Class[]{Object.class, long.class, Class.class, Object.class, Object.class}
			);
		}

		public boolean execute(Object obj, long offset, Class<?> clz, Object v1, Object v2) throws Throwable {
			Object returned = null;
			try {
				returned = method.invoke(myUnsafe, obj, offset, clz, v1, v2);
			} catch (InvocationTargetException exception) {
				throw exception.getCause();
			}
			boolean result = false;
			if (returned instanceof Boolean) {
				result = (Boolean)returned;
			} else {
				result = (returned == v1);
			}
			return result;
		}

		public String toString() {
			return "CompareAndDoSomethingFunction(" + method.getName() + ")";
		}
	}

	@DataProvider(name = "compareAndDoSomethingFuncs")
	static public Object[][] compareAndDoSomethingFuncs() throws NoSuchMethodException {
		return new Object[][] {
			{new CompareAndDoSomethingFunction("compareAndSetValue")},
			{new CompareAndDoSomethingFunction("compareAndExchangeValue")},
		};
	}

	@Test
	static public void testFlattenedFieldIsFlattened() throws Throwable {
		boolean isFlattened = myUnsafe.isFlattened(vtPoint.getClass().getDeclaredField("x"));
		assertEquals(isFlattened, isFlatteningEnabled);
	}

	@Test
	static public void testFlattenedArrayIsFlattened() throws Throwable {
		boolean isArrayFlattened = myUnsafe.isFlattenedArray(vtPointAry.getClass());
		assertEquals(isArrayFlattened, isArrayFlatteningEnabled);
	}

	@Test
	static public void testRegularArrayIsNotFlattened() throws Throwable {
		ClassTypePoint2D[] ary = {};
		assertTrue(ary.getClass().isArray());
		boolean isArrayFlattened = myUnsafe.isFlattenedArray(ary.getClass());
		assertFalse(isArrayFlattened);
	}

	@Test
	static public void testRegularFieldIsNotFlattened() throws Throwable {
		ClassTypePoint2D p = new ClassTypePoint2D(1, 1);
		boolean isFlattened = myUnsafe.isFlattened(p.getClass().getDeclaredField("x"));
		assertFalse(isFlattened);
	}

	@Test
	static public void testUnflattenableFieldInVTIsNotFlattened() throws Throwable {
		ValueClassPoint2D p = new ValueClassPoint2D(new ValueClassInt(1), new ValueClassInt(2));
		boolean isFlattened = myUnsafe.isFlattened(p.getClass().getDeclaredField("x"));
		assertFalse(isFlattened);
	}

	@Test
	static public void testFlattenedObjectIsNotFlattenedArray() throws Throwable {
		boolean isArrayFlattened = myUnsafe.isFlattenedArray(vtPoint.getClass());
		assertFalse(isArrayFlattened);
	}

	@Test
	static public void testNullIsNotFlattenedArray() throws Throwable {
		boolean isArrayFlattened = myUnsafe.isFlattenedArray(null);
		assertFalse(isArrayFlattened);
	}

	@Test
	static public void testPassingNullToIsFlattenedThrowsException() throws Throwable {
		assertThrows(NullPointerException.class, () -> {
			myUnsafe.isFlattened(null);
		});
	}

	@Test
	static public void testValueHeaderSizeOfValueType() throws Throwable {
		long headerSize = myUnsafe.valueHeaderSize(ValueTypePoint2D.class);
		if (isCompressedRefsEnabled) {
			assertEquals(headerSize, 4);
		} else {
			assertEquals(headerSize, 8);
		}
	}

	@Test
	static public void testValueHeaderSizeOfClassWithLongField() throws Throwable {
		long headerSize = myUnsafe.valueHeaderSize(ValueTypeWithLongField.class);
		if (isCompressedRefsEnabled && !isFlatteningEnabled) {
			assertEquals(headerSize, 4);
		} else {
			// In compressed refs mode, when the class has 8 byte field(s) only, 4 bytes of padding are added to the class. These padding bytes are included in the headerSize.
			assertEquals(headerSize, 8);
		}
	}

	@Test
	static public void testValueHeaderSizeIsDifferenceOfGetObjectSizeAndDataSize() {
		long dataSize = isCompressedRefsEnabled ? 8 : 16; // when compressed refs are enabled, references and flattened ints will take up 4 bytes of space each. When compressed refs are not enabled they will each be 8 bytes long.
		long headerSize = isCompressedRefsEnabled ? 4 : 8; // just like data size, the header size will also depend on whether compressed refs are enabled
		assertEquals(myUnsafe.valueHeaderSize(vtPoint.getClass()), headerSize);
		assertEquals(myUnsafe.valueHeaderSize(vtPoint.getClass()), myUnsafe.getObjectSize(vtPoint) - dataSize);
	}

	@Test
	static public void testValueHeaderSizeOfNonValueType() throws Throwable {
		assertEquals(myUnsafe.valueHeaderSize(ClassTypePoint2D.class), 0);
	}

	@Test
	static public void testValueHeaderSizeOfNull() throws Throwable {
		assertEquals(myUnsafe.valueHeaderSize(null), 0);
	}

	@Test
	static public void testValueHeaderSizeOfVTArray() throws Throwable {
		long size = myUnsafe.valueHeaderSize(vtIntAry.getClass());
		assertEquals(size, 0);
	}

	@Test
	static public void testValueHeaderSizeOfArray() throws Throwable {
		ClassTypePoint2D[] ary = {};
		long size = myUnsafe.valueHeaderSize(ary.getClass());
		assertEquals(size, 0);
	}

	@Test
	static public void testUninitializedDefaultValueOfValueType() throws Throwable {
		// create a new ValueTypePoint2D to ensure that the class has been initialized befoere myUnsafe.uninitializedDefaultValue is called
		ValueTypePoint2D! newPoint = new ValueTypePoint2D(new ValueInt(1), new ValueInt(1));
		ValueTypePoint2D! p = myUnsafe.uninitializedDefaultValue(newPoint.getClass());
		assertEquals(p.x.i, 0);
		assertEquals(p.y.i, 0);
	}

	@Test
	static public void testUninitializedDefaultValueOfNonValueType() throws Throwable {
		ClassTypePoint2D p = myUnsafe.uninitializedDefaultValue(ClassTypePoint2D.class);
		assertNull(p);
	}

	@Test
	static public void testUninitializedDefaultValueOfNull() throws Throwable {
		Object o = myUnsafe.uninitializedDefaultValue(null);
		assertNull(o);
	}

	@Test
	static public void testUninitializedDefaultValueOfVTArray() throws Throwable {
		Object o = myUnsafe.uninitializedDefaultValue(vtIntAry.getClass());
		assertNull(o);
	}

	@Test
	static public void testUninitializedDefaultValueOfArray() throws Throwable {
		ClassTypePoint2D[] ary = {};
		Object o = myUnsafe.uninitializedDefaultValue(ary.getClass());
		assertNull(o);
	}

	@Test
	static public void testuninitializedVTClassHasNullDefaultValue() {
		value class NeverInitialized {
			ValueInt! i;
			public implicit NeverInitialized();
			NeverInitialized(ValueInt! i) { this.i = i; }
		}
		assertNull(myUnsafe.uninitializedDefaultValue(NeverInitialized.class));
	}

	@Test
	static public void testNullObjGetValue() throws Throwable {
		assertThrows(NullPointerException.class, () -> {
			myUnsafe.getValue(null, 0, ValueInt.class);
		});
	}

	@Test
	static public void testNullClzGetValue() throws Throwable {
		assertThrows(NullPointerException.class, () -> {
			myUnsafe.getValue(vtPoint, vtPointOffsetX, null);
		});
	}

	@Test
	static public void testNonVTClzGetValue() throws Throwable {
		assertNull(myUnsafe.getValue(vtPoint, intWrapperOffsetVti, IntWrapper.class));
	}

	@Test
	static public void testGetValuesOfArray() throws Throwable {
		if (isFlatteningEnabled) {
			ValueTypePoint2D! p = myUnsafe.getValue(vtPointAry, vtPointAryOffset0, ValueTypePoint2D.class);
			assertEquals(p.x.i, vtPointAry[0].x.i);
			assertEquals(p.y.i, vtPointAry[0].y.i);
			p = myUnsafe.getValue(vtPointAry, vtPointAryOffset1, ValueTypePoint2D.class);
			assertEquals(p.x.i, vtPointAry[1].x.i);
			assertEquals(p.y.i, vtPointAry[1].y.i);
		}
	}

	static public void testGetValueOfZeroSizeVTArrayDoesNotCauseError() throws Throwable {
		ZeroSizeValueType![] zsvtAry = new ZeroSizeValueType![2];
		zsvtAry[0] = new ZeroSizeValueType();
		zsvtAry[1] = new ZeroSizeValueType();

		long zsvtAryOffset0 = myUnsafe.arrayBaseOffset(zsvtAry.getClass());
		assertNotNull(myUnsafe.getValue(zsvtAry, zsvtAryOffset0, ZeroSizeValueType.class));
	}

	static public void testGetValueOfZeroSizeVTObjectDoesNotCauseError() throws Throwable {
		ZeroSizeValueTypeWrapper! zsvtw = new ZeroSizeValueTypeWrapper();
		long zsvtwOffset0 = myUnsafe.objectFieldOffset(ZeroSizeValueTypeWrapper.class.getDeclaredField("z"));
		assertNotNull(myUnsafe.getValue(zsvtw, zsvtwOffset0, ZeroSizeValueType.class));
	}

	@Test
	static public void testGetValuesOfObject() throws Throwable {
		ValueInt! x = myUnsafe.getValue(vtPoint, vtPointOffsetX, ValueInt.class);
		ValueInt! y = myUnsafe.getValue(vtPoint, vtPointOffsetY, ValueInt.class);
		if (isFlatteningEnabled) {
			assertEquals(x.i, vtPoint.x.i);
			assertEquals(y.i, vtPoint.y.i);
		}
	}

	@Test
	static public void testGetValueOnVTWithLongFields() throws Throwable {
		ValueTypeLongPoint2D! vtLongPoint = new ValueTypeLongPoint2D(123, 456);
		ValueLong! x = myUnsafe.getValue(vtLongPoint, vtLongPointOffsetX, ValueLong.class);
		ValueLong! y = myUnsafe.getValue(vtLongPoint, vtLongPointOffsetY, ValueLong.class);

		if (isFlatteningEnabled) {
			assertEquals(x.l, vtLongPoint.x.l);
			assertEquals(y.l, vtLongPoint.y.l);
		}

		assertEquals(vtLongPoint.x.l, 123);
		assertEquals(vtLongPoint.y.l, 456);
	}

	@Test
	static public void testGetValueOnNonVTObj() throws Throwable {
		if (isFlatteningEnabled) {
			IntWrapper iw = new IntWrapper(7);
			ValueInt! vti = myUnsafe.getValue(iw, intWrapperOffsetVti, ValueInt.class);
			assertEquals(vti.i, iw.vti.i);
			assertEquals(iw.vti.i, 7);
		}
	}

	@Test
	static public void testNullObjPutValue() throws Throwable {
		assertThrows(NullPointerException.class, () -> {
			myUnsafe.putValue(null, vtPointOffsetX, ValueInt.class, new ValueInt(1));
		});
	}

	@Test
	static public void testNullClzPutValue() throws Throwable {
		assertThrows(NullPointerException.class, () -> {
			myUnsafe.putValue(vtPoint, vtPointOffsetX, null, new ValueInt(1));
		});
	}

	@Test
	static public void testNullValuePutValue() throws Throwable {
		assertThrows(NullPointerException.class, () -> {
			myUnsafe.putValue(vtPoint, vtPointOffsetX, ValueInt.class, null);
		});
	}

	@Test
	static public void testPutValueWithNonVTclzAndValue() throws Throwable {
		int xBeforeTest = vtPoint.x.i;
		assertNotEquals(xBeforeTest, 10000);
		myUnsafe.putValue(vtPoint, vtPointOffsetX, IntWrapper.class, new IntWrapper(10000));
		assertEquals(vtPoint.x.i, xBeforeTest);
	}

	@Test
	static public void testPutValueWithNonVTClzButVTValue() throws Throwable {
		int xBeforeTest = vtPoint.x.i;
		assertNotEquals(xBeforeTest, 10000);
		myUnsafe.putValue(vtPoint, vtPointOffsetX, IntWrapper.class, new ValueInt(10000));
		assertEquals(vtPoint.x.i, xBeforeTest);
	}

	@Test
	static public void testPutValueWithNonVTObj() throws Throwable {
		if (isFlatteningEnabled) {
			IntWrapper iw = new IntWrapper(7);
			ValueInt! newVal = new ValueInt(5892);
			myUnsafe.putValue(iw, intWrapperOffsetVti, ValueInt.class, newVal);
			assertEquals(iw.vti.i, newVal.i);
			assertEquals(newVal.i, 5892);
		}
	}

	@Test
	static public void testPutValuesOfArray() throws Throwable {
		if (isFlatteningEnabled) {
			ValueTypePoint2D! p = new ValueTypePoint2D(new ValueInt(34857), new ValueInt(784382));
			myUnsafe.putValue(vtPointAry, vtPointAryOffset0, ValueTypePoint2D.class, p);
			assertEquals(vtPointAry[0].x.i, p.x.i);
			assertEquals(vtPointAry[0].y.i, p.y.i);
			myUnsafe.putValue(vtPointAry, vtPointAryOffset1, ValueTypePoint2D.class, p);
			assertEquals(vtPointAry[1].x.i, p.x.i);
			assertEquals(vtPointAry[1].y.i, p.y.i);
			assertEquals(p.x.i, 34857);
			assertEquals(p.y.i, 784382);
		}
	}

	@Test
	static public void testPutValueWithZeroSizeVTArrayDoesNotCauseError() throws Throwable {
		ZeroSizeValueType[] zsvtAry = new ZeroSizeValueType[] {
			new ZeroSizeValueType(),
			new ZeroSizeValueType()
		};
		long zsvtAryOffset0 = myUnsafe.arrayBaseOffset(zsvtAry.getClass());
		myUnsafe.putValue(zsvtAry, zsvtAryOffset0, ZeroSizeValueType.class, new ZeroSizeValueType());
	}

	@Test
	static public void testPutValueOfZeroSizeVTObjectDoesNotCauseError() throws Throwable {
		ZeroSizeValueTypeWrapper! zsvtw = new ZeroSizeValueTypeWrapper();
		long zsvtwOffset0 = myUnsafe.objectFieldOffset(ZeroSizeValueTypeWrapper.class.getDeclaredField("z"));
		myUnsafe.putValue(zsvtw, zsvtwOffset0, ZeroSizeValueType.class, new ZeroSizeValueType());
	}

	@Test
	static public void testPutValuesOfObject() throws Throwable {
		ValueInt! newI = new ValueInt(47538);
		myUnsafe.putValue(vtPoint, vtPointOffsetX, ValueInt.class, newI);
		myUnsafe.putValue(vtPoint, vtPointOffsetY, ValueInt.class, newI);
		if (isFlatteningEnabled) {
			assertEquals(vtPoint.x.i, newI.i);
			assertEquals(vtPoint.y.i, newI.i);
		}
		assertEquals(newI.i, 47538);
	}

	@Test
	static public void testPutValueOnVTWithLongFields() throws Throwable {
		ValueTypeLongPoint2D! vtLongPoint = new ValueTypeLongPoint2D(123, 456);
		ValueLong! newVal = new ValueLong(23427);
		myUnsafe.putValue(vtLongPoint, vtLongPointOffsetX, ValueLong.class, newVal);
		myUnsafe.putValue(vtLongPoint, vtLongPointOffsetY, ValueLong.class, newVal);
		if (isFlatteningEnabled) {
			assertEquals(vtLongPoint.y.l, newVal.l);
			assertEquals(vtLongPoint.x.l, newVal.l);
		}
		assertEquals(newVal.l, 23427);
	}

	@Test
	static public void testGetSizeOfVT() {
		long size = myUnsafe.getObjectSize(vtPoint);
		if (isCompressedRefsEnabled) {
			assertEquals(size, 12);
		} else {
			assertEquals(size, 24);
		}
	}

	@Test
	static public void testGetSizeOfNonVT() {
		long size = myUnsafe.getObjectSize(new IntWrapper(5));
		if (isCompressedRefsEnabled) {
			assertEquals(size, 16);
		} else {
			assertEquals(size, 24);
		}
	}

	@Test
	static public void testGetSizeOfVTWithLongFields() {
		long size = myUnsafe.getObjectSize(new ValueTypeLongPoint2D(123, 456));
		if (isCompressedRefsEnabled && !isFlatteningEnabled) {
			assertEquals(size, 12);
		} else {
			assertEquals(size, 24);
		}
	}

	@Test
	static public void testGetSizeOfArray() {
		long size = myUnsafe.getObjectSize(vtIntAry);

		int headerSizeAdjustment = (!isDualHeaderShapeDisabled && isGcPolicyGencon) ? 8 : 0;
		if (isCompressedRefsEnabled) {
			assertEquals(size, 24 - headerSizeAdjustment);
		} else {
			assertEquals(size, 40 - headerSizeAdjustment);
		}
	}

	@Test
	static public void testGetSizeOfZeroSizeVT() {
		long size = myUnsafe.getObjectSize(new ZeroSizeValueType());
		if (isCompressedRefsEnabled) {
			assertEquals(size, 4);
		} else {
			assertEquals(size, 8);
		}
	}

	@Test(dataProvider = "compareAndDoSomethingFuncs")
	static public void testCompareAndSetNullObj(CompareAndDoSomethingFunction compareAndSwapValue) throws Throwable {
		assertThrows(NullPointerException.class, () -> {
			compareAndSwapValue.execute(null, vtPointOffsetX, ValueInt.class, vtPoint.x, new ValueInt(1));
		});
	}

	@Test(dataProvider = "compareAndDoSomethingFuncs")
	static public void testCompareAndSetNullClz(CompareAndDoSomethingFunction compareAndSwapValue) throws Throwable {
		if (isFlatteningEnabled) {
			assertThrows(NullPointerException.class, () -> {
				compareAndSwapValue.execute(vtPoint, vtPointOffsetX, null, vtPoint.x, new ValueInt(1));
			});
		} else {
			boolean result = compareAndSwapValue.execute(vtPoint, vtPointOffsetX, null, vtPoint.x, new ValueInt(1));
			assertTrue(result);
			assertEquals(vtPoint.x.i, 1);
		}
	}

	@Test(dataProvider = "compareAndDoSomethingFuncs")
	static public void testCompareAndSetNullV1(CompareAndDoSomethingFunction compareAndSwapValue) throws Throwable {
		int original = vtPoint.x.i;
		boolean success = compareAndSwapValue.execute(vtPoint, vtPointOffsetX, ValueInt.class, null, new ValueInt(1));
		assertFalse(success);
		assertEquals(vtPoint.x.i, original);
	}

	@Test(dataProvider = "compareAndDoSomethingFuncs")
	static public void testCompareAndSetNullV2(CompareAndDoSomethingFunction compareAndSwapValue) throws Throwable {
		int original = vtPoint.x.i;
		boolean success = compareAndSwapValue.execute(vtPoint, vtPointOffsetX, ValueInt.class, new ValueInt(original + 1), null);
		assertFalse(success);
		assertEquals(vtPoint.x.i, original);

		if (isFlatteningEnabled) {
			assertThrows(NullPointerException.class, () -> {
				compareAndSwapValue.execute(vtPoint, vtPointOffsetX, ValueInt.class, vtPoint.x, null);
			});
		} else {
			success = compareAndSwapValue.execute(vtPoint, vtPointOffsetX, ValueInt.class, vtPoint.x, null);
			assertTrue(success);
			assertEquals(vtPoint.x, null);
		}
	}

	@Test(dataProvider = "compareAndDoSomethingFuncs")
	static public void testCompareAndSetPointXSuccess(CompareAndDoSomethingFunction compareAndSwapValue) throws Throwable {
		int original = vtPoint.x.i;
		ValueInt! newVti = new ValueInt(328);
		boolean success = compareAndSwapValue.execute(vtPoint, vtPointOffsetX, ValueInt.class, vtPoint.x, newVti);
		assertEquals(newVti.i, 328);
		assertTrue(success);
		assertEquals(vtPoint.x.i, newVti.i);
	}

	@Test(dataProvider = "compareAndDoSomethingFuncs")
	static public void testCompareAndSetPointYSuccess(CompareAndDoSomethingFunction compareAndSwapValue) throws Throwable {
		int original = vtPoint.y.i;
		ValueInt! newVti = new ValueInt(328);
		boolean success = compareAndSwapValue.execute(vtPoint, vtPointOffsetY, ValueInt.class, vtPoint.y, newVti);
		assertEquals(newVti.i, 328);
		assertTrue(success);
		assertEquals(vtPoint.y.i, newVti.i);
	}

	@Test(dataProvider = "compareAndDoSomethingFuncs")
	static public void testCompareAndSetPointXFailure(CompareAndDoSomethingFunction compareAndSwapValue) throws Throwable {
		int original = vtPoint.x.i;
		ValueInt! newVti = new ValueInt(328);
		boolean success = compareAndSwapValue.execute(vtPoint, vtPointOffsetX, ValueInt.class, new ValueInt(original + 1), newVti);
		assertEquals(newVti.i, 328);
		assertFalse(success);
		assertEquals(vtPoint.x.i, original);
	}

	@Test(dataProvider = "compareAndDoSomethingFuncs")
	static public void testCompareAndSetPointYFailure(CompareAndDoSomethingFunction compareAndSwapValue) throws Throwable {
		int original = vtPoint.y.i;
		ValueInt! newVti = new ValueInt(328);
		boolean success = compareAndSwapValue.execute(vtPoint, vtPointOffsetY, ValueInt.class, new ValueInt(original + 1), newVti);
		assertEquals(newVti.i, 328);
		assertFalse(success);
		assertEquals(vtPoint.y.i, original);
	}

	@Test(dataProvider = "compareAndDoSomethingFuncs")
	static public void testCompareAndSetArray0Success(CompareAndDoSomethingFunction compareAndSwapValue) throws Throwable {
		int original = vtIntAry[0].i;
		ValueInt! newVti = new ValueInt(456);
		boolean success = compareAndSwapValue.execute(vtIntAry, vtIntAryOffset0, ValueInt.class, vtIntAry[0], newVti);
		assertEquals(newVti.i, 456);
		assertTrue(success);
		assertEquals(vtIntAry[0].i, newVti.i);
	}

	@Test(dataProvider = "compareAndDoSomethingFuncs")
	static public void testCompareAndSetArray1Success(CompareAndDoSomethingFunction compareAndSwapValue) throws Throwable {
		int original = vtIntAry[1].i;
		ValueInt! newVti = new ValueInt(456);
		boolean success = compareAndSwapValue.execute(vtIntAry, vtIntAryOffset1, ValueInt.class, vtIntAry[1], newVti);
		assertEquals(newVti.i, 456);
		assertTrue(success);
		assertEquals(vtIntAry[1].i, newVti.i);
	}

	@Test(dataProvider = "compareAndDoSomethingFuncs")
	static public void testCompareAndSetArray0Failure(CompareAndDoSomethingFunction compareAndSwapValue) throws Throwable {
		int original = vtIntAry[0].i;
		ValueInt! newVti = new ValueInt(328);
		boolean success = compareAndSwapValue.execute(vtIntAry, vtIntAryOffset0, ValueInt.class, new ValueInt(original + 1), newVti);
		assertEquals(newVti.i, 328);
		assertFalse(success);
		assertEquals(vtIntAry[0].i, original);
	}

	@Test(dataProvider = "compareAndDoSomethingFuncs")
	static public void testCompareAndSetArray1Failure(CompareAndDoSomethingFunction compareAndSwapValue) throws Throwable {
		int original = vtIntAry[1].i;
		ValueInt! newVti = new ValueInt(328);
		boolean success = compareAndSwapValue.execute(vtIntAry, vtIntAryOffset1, ValueInt.class, new ValueInt(original + 1), newVti);
		assertEquals(newVti.i, 328);
		assertFalse(success);
		assertEquals(vtIntAry[1].i, original);
	}

	@Test(dataProvider = "compareAndDoSomethingFuncs")
	static public void testCompareAndSetOnVTWithLongFields(CompareAndDoSomethingFunction compareAndSwapValue) throws Throwable {
		ValueTypeLongPoint2D! vtLongPoint = new ValueTypeLongPoint2D(123, 456);
		assertEquals(vtLongPoint.x.l, 123);
		assertEquals(vtLongPoint.y.l, 456);

		long original = vtLongPoint.x.l;
		ValueLong! newVtl = new ValueLong(372);
		boolean success = compareAndSwapValue.execute(vtLongPoint, vtLongPointOffsetX, ValueLong.class, vtLongPoint.x, newVtl);
		assertEquals(newVtl.l, 372);
		assertTrue(success);
		assertEquals(vtLongPoint.x.l, newVtl.l);
	}

	@Test(dataProvider = "compareAndDoSomethingFuncs")
	static public void testCompareAndSetWrongClz(CompareAndDoSomethingFunction compareAndSwapValue) throws Throwable {
		int original = vtPoint.x.i;
		ValueInt! newVti = new ValueInt(328);
		boolean success = compareAndSwapValue.execute(vtPoint, vtPointOffsetX, ValueInt2.class, vtPoint.x, newVti);
		assertEquals(newVti.i, 328);
		if (isFlatteningEnabled) {
			assertFalse(success);
			assertEquals(vtPoint.x.i, original);
		} else {
			assertTrue(success);
			assertEquals(vtPoint.x.i, newVti.i);
		}
	}

	@Test
	static public void testGetAndSetNullObj() throws Throwable {
		assertThrows(NullPointerException.class, () -> {
			myUnsafe.getAndSetValue(null, vtPointOffsetX, ValueInt.class, new ValueInt(1));
		});
	}

	@Test
	static public void testGetAndSetNullClz() throws Throwable {
		if (isFlatteningEnabled) {
			assertThrows(NullPointerException.class, () -> {
				myUnsafe.getAndSetValue(vtPoint, vtPointOffsetX, null, new ValueInt(1));
			});
		} else {
			int original = vtPoint.x.i;
			Object result = myUnsafe.getAndSetValue(vtPoint, vtPointOffsetX, null, new ValueInt(1));
			assertEquals(((ValueInt!)result).i, original);
			assertEquals(vtPoint.x.i, 1);
		}
	}

	@Test
	static public void testGetAndSetNullV() throws Throwable {
		int original = vtPoint.x.i;

		if (isFlatteningEnabled) {
			assertThrows(NullPointerException.class, () -> {
				myUnsafe.getAndSetValue(vtPoint, vtPointOffsetX, ValueInt.class, null);
			});
		} else {
			Object result = myUnsafe.getAndSetValue(vtPoint, vtPointOffsetX, ValueInt.class, null);
			assertEquals(((ValueInt!)result).i, original);
			assertEquals(vtPoint.x, null);
		}
	}

	@Test
	static public void testGetAndSetPointX() throws Throwable {
		int original = vtPoint.x.i;
		ValueInt! newVti = new ValueInt(328);
		Object result = myUnsafe.getAndSetValue(vtPoint, vtPointOffsetX, ValueInt.class, newVti);
		assertEquals(newVti.i, 328);
		assertEquals(((ValueInt!)result).i, original);
		assertEquals(vtPoint.x.i, newVti.i);
	}

	@Test
	static public void testGetAndSetPointY() throws Throwable {
		int original = vtPoint.y.i;
		ValueInt! newVti = new ValueInt(328);
		Object result = myUnsafe.getAndSetValue(vtPoint, vtPointOffsetY, ValueInt.class, newVti);
		assertEquals(newVti.i, 328);
		assertEquals(((ValueInt!)result).i, original);
		assertEquals(vtPoint.y.i, newVti.i);
	}

	@Test
	static public void testGetAndSetArray0() throws Throwable {
		int original = vtIntAry[0].i;
		ValueInt! newVti = new ValueInt(456);
		Object result = myUnsafe.getAndSetValue(vtIntAry, vtIntAryOffset0, ValueInt.class, newVti);
		assertEquals(newVti.i, 456);
		assertEquals(((ValueInt!)result).i, original);
		assertEquals(vtIntAry[0].i, newVti.i);
	}

	@Test
	static public void testGetAndSetArray1() throws Throwable {
		int original = vtIntAry[1].i;
		ValueInt! newVti = new ValueInt(456);
		Object result = myUnsafe.getAndSetValue(vtIntAry, vtIntAryOffset1, ValueInt.class, newVti);
		assertEquals(newVti.i, 456);
		assertEquals(((ValueInt!)result).i, original);
		assertEquals(vtIntAry[1].i, newVti.i);
	}

	@Test
	static public void testGetAndSetOnVTWithLongFields() throws Throwable {
		ValueTypeLongPoint2D! vtLongPoint = new ValueTypeLongPoint2D(123, 456);
		assertEquals(vtLongPoint.x.l, 123);
		assertEquals(vtLongPoint.y.l, 456);

		long original = vtLongPoint.x.l;
		ValueLong! newVtl = new ValueLong(372);
		Object result = myUnsafe.getAndSetValue(vtLongPoint, vtLongPointOffsetX, ValueLong.class, newVtl);
		assertEquals(newVtl.l, 372);
		assertEquals(((ValueLong!)result).l, original);
		assertEquals(vtLongPoint.x.l, newVtl.l);
	}
}
