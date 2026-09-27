package com.evops.workorder.exchange;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CSV 编解码：转义规则与往返一致性的单元测试。 */
class CsvCodecTest {

    @Test
    void encodeField_plainValue_unquoted() {
        assertEquals("abc", CsvCodec.encodeField("abc"));
        assertEquals("播种", CsvCodec.encodeField("播种"));
        assertEquals("", CsvCodec.encodeField(null));
        assertEquals("", CsvCodec.encodeField(""));
    }

    @Test
    void encodeField_specialChars_quotedAndEscaped() {
        assertEquals("\"a,b\"", CsvCodec.encodeField("a,b"));
        assertEquals("\"说\"\"你好\"\"\"", CsvCodec.encodeField("说\"你好\""));
        assertEquals("\"a\nb\"", CsvCodec.encodeField("a\nb"));
        assertEquals("\"a\r\nb\"", CsvCodec.encodeField("a\r\nb"));
    }

    @Test
    void roundTrip_specialFields_identical() {
        List<String> fields = Arrays.asList("WO-1", "M-1", "收割,秸秆还田", "说\"你好\"", "第一行\n第二行", "", "末尾,");
        String line = CsvCodec.encodeRecord(fields);
        List<CsvCodec.CsvRecord> records = CsvCodec.parse(line + "\r\n");
        assertEquals(1, records.size());
        assertEquals(fields, records.get(0).getFields());
    }

    @Test
    void parse_stripsBom() {
        List<CsvCodec.CsvRecord> records = CsvCodec.parse(CsvCodec.BOM + "a,b\r\nc,d\r\n");
        assertEquals(2, records.size());
        assertEquals(Arrays.asList("a", "b"), records.get(0).getFields());
    }

    @Test
    void parse_acceptsCrLfLfCr() {
        List<CsvCodec.CsvRecord> records = CsvCodec.parse("a,b\r\nc,d\ne,f\rg,h");
        assertEquals(4, records.size());
        assertEquals(Arrays.asList("g", "h"), records.get(3).getFields());
    }

    @Test
    void parse_quotedNewline_keepsContentAndTracksPhysicalLine() {
        // 第一条记录字段内含换行，占物理行 2-3；第二条记录起始物理行应为 4
        String text = "header1,header2\r\n\"x\ny\",z\r\np,q\r\n";
        List<CsvCodec.CsvRecord> records = CsvCodec.parse(text);
        assertEquals(3, records.size());
        assertEquals("x\ny", records.get(1).getFields().get(0));
        assertEquals(2, records.get(1).getStartLine());
        assertEquals(4, records.get(2).getStartLine());
    }

    @Test
    void parse_unclosedQuote_throwsWithLineNumber() {
        CsvCodec.CsvFormatException ex = assertThrows(CsvCodec.CsvFormatException.class,
                () -> CsvCodec.parse("a,b\r\n\"unclosed,c\r\n"));
        assertTrue(ex.getMessage().contains("第 2 行"), ex.getMessage());
    }

    @Test
    void parse_garbageAfterQuote_throws() {
        CsvCodec.CsvFormatException ex = assertThrows(CsvCodec.CsvFormatException.class,
                () -> CsvCodec.parse("\"abc\"def,x"));
        assertTrue(ex.getMessage().contains("非法字符"), ex.getMessage());
    }

    @Test
    void parse_blankLine_detected() {
        List<CsvCodec.CsvRecord> records = CsvCodec.parse("a,b\r\n\r\n  \r\nc,d\r\n");
        assertEquals(4, records.size());
        assertTrue(records.get(1).isBlank());
        assertTrue(records.get(2).isBlank());
        assertEquals(4, records.get(3).getStartLine());
    }

    @Test
    void parse_emptyOrNull_returnsEmpty() {
        assertTrue(CsvCodec.parse(null).isEmpty());
        assertTrue(CsvCodec.parse("").isEmpty());
    }
}
