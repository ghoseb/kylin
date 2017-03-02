package org.apache.kylin.dict;

/**
 * Created by shyamshinde on 2/22/17.
 */


import org.apache.kylin.common.util.Bytes;
import org.apache.kylin.dimension.Dictionary;
import org.junit.Test;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;


public class MultipleDictionaryValueEnumeratorTest {

    private static DictionaryInfo createDictInfo(int[] values) {
        MockDictionary mockDict = new MockDictionary();
        mockDict.values = values;
        DictionaryInfo info = new DictionaryInfo();
        info.setDictionaryObject(mockDict);
        return info;
    }

    private static Integer[] enumerateDictInfoList(List<DictionaryInfo> dictionaryInfoList) throws IOException {
        MultipleDictionaryValueEnumerator enumerator = new MultipleDictionaryValueEnumerator(dictionaryInfoList);
        List<Integer> values = new ArrayList<>();
        while (enumerator.moveNext()) {
            values.add(Bytes.toInt(enumerator.current()));
        }
        return values.toArray(new Integer[0]);
    }

    @Test
    public void testNormalDicts() throws IOException {
        List<DictionaryInfo> dictionaryInfoList = new ArrayList<>(2);
        dictionaryInfoList.add(createDictInfo(new int[]{0, 1, 2}));
        dictionaryInfoList.add(createDictInfo(new int[]{4, 5, 6}));

        Integer[] values = enumerateDictInfoList(dictionaryInfoList);
        assertEquals(6, values.length);
        assertArrayEquals(new Integer[]{0, 1, 2, 4, 5, 6}, values);
    }

    @Test
    public void testFirstEmptyDicts() throws IOException {
        List<DictionaryInfo> dictionaryInfoList = new ArrayList<>(2);
        dictionaryInfoList.add(createDictInfo(new int[]{}));
        dictionaryInfoList.add(createDictInfo(new int[]{4, 5, 6}));

        Integer[] values = enumerateDictInfoList(dictionaryInfoList);
        assertEquals(3, values.length);
        assertArrayEquals(new Integer[]{4, 5, 6}, values);
    }

    @Test
    public void testMiddleEmptyDicts() throws IOException {
        List<DictionaryInfo> dictionaryInfoList = new ArrayList<>(3);
        dictionaryInfoList.add(createDictInfo(new int[]{0, 1, 2}));
        dictionaryInfoList.add(createDictInfo(new int[]{}));
        dictionaryInfoList.add(createDictInfo(new int[]{7, 8, 9}));

        Integer[] values = enumerateDictInfoList(dictionaryInfoList);
        assertEquals(6, values.length);
        assertArrayEquals(new Integer[]{0, 1, 2, 7, 8, 9}, values);
    }

    @Test
    public void testLastEmptyDicts() throws IOException {
        List<DictionaryInfo> dictionaryInfoList = new ArrayList<>(3);
        dictionaryInfoList.add(createDictInfo(new int[]{0, 1, 2}));
        dictionaryInfoList.add(createDictInfo(new int[]{6, 7, 8}));
        dictionaryInfoList.add(createDictInfo(new int[]{}));

        Integer[] values = enumerateDictInfoList(dictionaryInfoList);
        assertEquals(6, values.length);
        assertArrayEquals(new Integer[]{0, 1, 2, 6, 7, 8}, values);
    }

    public static class MockDictionary extends Dictionary {
        public int[] values;

        @Override
        public int getMinId() {
            return 0;
        }

        @Override
        public int getMaxId() {
            return values.length-1;
        }

        @Override
        public int getSizeOfId() {
            return 4;
        }

        @Override
        public int getSizeOfValue() {
            return 4;
        }

        @Override
        protected int getIdFromValueImpl(Object value, int roundingFlag) {
            return 0;
        }

        @Override
        protected Object getValueFromIdImpl(int id) {
            return null;
        }

        @Override
        protected int getIdFromValueBytesImpl(byte[] value, int offset, int len, int roundingFlag) {
            return 0;
        }

        @Override
        protected byte[] getValueBytesFromIdImpl(int id) {
            return null;
        }

        @Override
        protected int getValueBytesFromIdImpl(int id, byte[] returnValue, int offset) {
            System.arraycopy(Bytes.toBytes(values[id]), 0, returnValue, offset, 4);
            return 4;
        }

        @Override
        public void dump(PrintStream out) {}

        @Override
        public void write(DataOutput out) throws IOException {}

        @Override
        public void readFields(DataInput in) throws IOException {}

        @Override
        public boolean contains(Dictionary another) {
            return false;
        }
    }

}
