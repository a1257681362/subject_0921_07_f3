package com.evops.workorder.exchange;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CSV 转义/解析口径：RFC 4180 子集，往返一致。 */
class CsvCodecTest {

    @Test
    void escape_plainField_untouched() {
        assertEquals("abc", CsvCodec.escape("abc"));
        assertEquals("播种", CsvCodec.escape("播种"));
        assertEquals("", CsvCodec.escape(""));
        assertEquals("", CsvCodec.escape(null));
    }

    @Test
    void escape_fieldWithComma_quoted() {
        assertEquals("\"a,b\"", CsvCodec.escape("a,b"));
    }

    @Test
    void escape_fieldWithQuote_doubledAndQuoted() {
        assertEquals("\"say \"\"hi\"\"\"", CsvCodec.escape("say \"hi\""));
    }

    @Test
    void escape_fieldWithNewline_quoted() {
        assertEquals("\"line1\nline2\"", CsvCodec.escape("line1\nline2"));
        assertEquals("\"a\r\nb\"", CsvCodec.escape("a\r\nb"));
    }

    @Test
    void parse_crlfAndLf_bothSupported() {
        List<List<String>> crlf = CsvCodec.parse("a,b\r\nc,d\r\n");
        assertEquals(Arrays.asList(Arrays.asList("a", "b"), Arrays.asList("c", "d")), crlf);

        List<List<String>> lf = CsvCodec.parse("a,b\nc,d\n");
        assertEquals(crlf, lf);
    }

    @Test
    void parse_quotedFieldWithCommaAndNewline() {
        List<List<String>> records = CsvCodec.parse("id,\"a,b\",\"x\ny\"\r\n");
        assertEquals(1, records.size());
        assertEquals(Arrays.asList("id", "a,b", "x\ny"), records.get(0));
    }

    @Test
    void parse_doubledQuote_unescaped() {
        List<List<String>> records = CsvCodec.parse("\"say \"\"hi\"\"\"\r\n");
        assertEquals(Arrays.asList(Arrays.asList("say \"hi\"")), records);
    }

    @Test
    void parse_bomSkipped() {
        List<List<String>> records = CsvCodec.parse(CsvCodec.BOM + "h1,h2\r\nv1,v2\r\n");
        assertEquals(Arrays.asList("h1", "h2"), records.get(0));
        assertEquals(Arrays.asList("v1", "v2"), records.get(1));
    }

    @Test
    void parse_emptyAndBlankLines() {
        assertTrue(CsvCodec.parse("").isEmpty());
        assertTrue(CsvCodec.parse(null).isEmpty());
        // 单个空行 = 一条空记录；末尾换行不再产生记录
        List<List<String>> records = CsvCodec.parse("a\r\n\r\nb\r\n");
        assertEquals(3, records.size());
        assertEquals(Arrays.asList("a"), records.get(0));
        assertEquals(Arrays.asList(""), records.get(1));
        assertEquals(Arrays.asList("b"), records.get(2));
    }

    @Test
    void parse_lastRecordWithoutTrailingNewline() {
        List<List<String>> records = CsvCodec.parse("a,b\nc,d");
        assertEquals(2, records.size());
        assertEquals(Arrays.asList("c", "d"), records.get(1));
    }

    @Test
    void roundTrip_encodeParse_identical() {
        List<List<String>> source = Arrays.asList(
                Arrays.asList("作业单号", "农机编码", "备注"),
                Arrays.asList("WO-1", "M-01", "含,逗号"),
                Arrays.asList("WO-2", "M-02", "含\"引号\""),
                Arrays.asList("WO-3", "M-03", "含\n换行"),
                Arrays.asList("WO-4", "M-04", ""),
                Arrays.asList("WO-5", "M-05", "  首尾空格  "));
        StringBuilder text = new StringBuilder();
        for (List<String> record : source) {
            text.append(CsvCodec.encodeRecord(record)).append(CsvCodec.CRLF);
        }
        assertEquals(source, CsvCodec.parse(text.toString()));
    }

    @Test
    void roundTrip_parseEncode_stable() {
        String text = CsvCodec.BOM + "h1,h2\r\n\"a,b\",\"c\"\"d\"\r\nx,\r\n";
        List<List<String>> records = CsvCodec.parse(text);
        StringBuilder reEncoded = new StringBuilder(CsvCodec.BOM);
        for (List<String> record : records) {
            reEncoded.append(CsvCodec.encodeRecord(record)).append(CsvCodec.CRLF);
        }
        // 再解析结果一致：编码是解析的规范化形式
        assertEquals(records, CsvCodec.parse(reEncoded.toString()));
    }
}
