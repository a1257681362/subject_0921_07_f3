package com.evops.workorder.exchange;

import java.util.ArrayList;
import java.util.List;

/**
 * CSV 编解码（RFC 4180 子集），作业单文件交换的统一口径：
 * <ul>
 *   <li>字段含逗号、双引号、回车或换行时，整体用双引号包围；字段内的双引号双写（""）。</li>
 *   <li>导出行尾为 CRLF；解析兼容 CRLF / LF，引号内允许换行（多行字段）。</li>
 *   <li>文本为 UTF-8；写出方带 BOM（Excel 直接打开不乱码），解析时自动跳过 BOM。</li>
 *   <li>空字段与空串同义：CSV 不区分 NULL 与空串，空字段导入按未填写处理。</li>
 *   <li>宽松解析：引号未从字段起始位置出现时按普通字符处理，不报错。</li>
 * </ul>
 * 纯函数、无外部依赖，转义/解析往返一致由单测保证。
 */
public final class CsvCodec {

    public static final char QUOTE = '"';
    public static final char COMMA = ',';
    public static final String CRLF = "\r\n";
    /** UTF-8 BOM，写出时置于文件头，解析时自动跳过。 */
    public static final String BOM = "\uFEFF";

    private CsvCodec() {
    }

    /** 转义单个字段；null 按空字段处理。 */
    public static String escape(String field) {
        if (field == null) {
            return "";
        }
        boolean needQuotes = field.indexOf(COMMA) >= 0 || field.indexOf(QUOTE) >= 0
                || field.indexOf('\r') >= 0 || field.indexOf('\n') >= 0;
        if (!needQuotes) {
            return field;
        }
        return QUOTE + field.replace("\"", "\"\"") + QUOTE;
    }

    /** 编码一条记录（不含行尾符，由调用方拼接）。 */
    public static String encodeRecord(List<String> fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sb.append(COMMA);
            }
            sb.append(escape(fields.get(i)));
        }
        return sb.toString();
    }

    /**
     * 解析整个 CSV 文本为记录列表，每条记录为字段列表。
     * 自动跳过 BOM；末尾换行不产生多余空记录；空行解析为单字段空串记录。
     */
    public static List<List<String>> parse(String content) {
        List<List<String>> records = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return records;
        }
        String s = content.startsWith(BOM) ? content.substring(BOM.length()) : content;
        int n = s.length();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean recordTouched = false;
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (inQuotes) {
                if (c == QUOTE) {
                    if (i + 1 < n && s.charAt(i + 1) == QUOTE) {
                        field.append(QUOTE);
                        i += 2;
                    } else {
                        inQuotes = false;
                        i++;
                    }
                } else {
                    field.append(c);
                    i++;
                }
            } else if (c == QUOTE && field.length() == 0) {
                inQuotes = true;
                recordTouched = true;
                i++;
            } else if (c == COMMA) {
                fields.add(field.toString());
                field.setLength(0);
                recordTouched = true;
                i++;
            } else if (c == '\r' || c == '\n') {
                fields.add(field.toString());
                field.setLength(0);
                records.add(fields);
                fields = new ArrayList<>();
                recordTouched = false;
                i++;
                if (c == '\r' && i < n && s.charAt(i) == '\n') {
                    i++;
                }
            } else {
                field.append(c);
                recordTouched = true;
                i++;
            }
        }
        if (recordTouched || field.length() > 0 || !fields.isEmpty()) {
            fields.add(field.toString());
            records.add(fields);
        }
        return records;
    }
}
