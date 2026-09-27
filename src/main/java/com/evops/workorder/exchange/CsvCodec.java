package com.evops.workorder.exchange;

import java.util.ArrayList;
import java.util.List;

/**
 * CSV 编解码器（RFC 4180 子集），作业单文件交换转义规则的唯一实现处。
 *
 * 规则（与 docs/work-order-csv-exchange.md 一致）：
 * - 字段含逗号、双引号、换行（\r 或 \n）时，整体用双引号包裹；字段内双引号双写（" → ""）。
 * - 编码统一 UTF-8；导出文件带 BOM，解析时自动剥离 BOM。
 * - 记录行尾 CRLF；解析兼容 CRLF / LF / CR。
 * - 引号包裹的字段内允许出现换行，原样保留。
 * - 行号按物理行计（表头为第 1 行），引号内换行同样推进物理行号。
 */
public final class CsvCodec {

    /** UTF-8 BOM，导出时置于文件头，解析时自动剥离。 */
    public static final String BOM = "\uFEFF";

    private CsvCodec() {
    }

    /** 一条解析出的记录：字段列表 + 起始物理行号（1 起，表头为第 1 行）。 */
    public static final class CsvRecord {
        private final List<String> fields;
        private final int startLine;

        CsvRecord(List<String> fields, int startLine) {
            this.fields = fields;
            this.startLine = startLine;
        }

        public List<String> getFields() {
            return fields;
        }

        public int getStartLine() {
            return startLine;
        }

        /** 全部字段去空白后均为空，视为空白行（导入时跳过，不计入数据行）。 */
        public boolean isBlank() {
            for (String field : fields) {
                if (field != null && !field.trim().isEmpty()) {
                    return false;
                }
            }
            return true;
        }
    }

    /** CSV 格式错误，message 带物理行号，便于按行定位。 */
    public static final class CsvFormatException extends RuntimeException {
        public CsvFormatException(String message) {
            super(message);
        }
    }

    /** 编码一条记录（不含行尾换行）。null 字段按空串处理。 */
    public static String encodeRecord(List<String> fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(encodeField(fields.get(i)));
        }
        return sb.toString();
    }

    /** 编码单个字段：需要时双引号包裹，内部双引号双写。 */
    public static String encodeField(String field) {
        if (field == null) {
            return "";
        }
        boolean needQuote = field.indexOf(',') >= 0 || field.indexOf('"') >= 0
                || field.indexOf('\r') >= 0 || field.indexOf('\n') >= 0;
        if (!needQuote) {
            return field;
        }
        return '"' + field.replace("\"", "\"\"") + '"';
    }

    /**
     * 解析整份 CSV 文本。自动剥离 BOM；兼容 CRLF / LF / CR 行尾；
     * 引号字段后紧跟非法字符、引号未闭合等抛 {@link CsvFormatException}（带行号）。
     */
    public static List<CsvRecord> parse(String text) {
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }
        Parser parser = new Parser(text);
        return parser.parse();
    }

    private static final class Parser {
        private final String text;
        private int pos;
        private int line = 1;

        Parser(String text) {
            String body = text;
            if (body.startsWith(BOM)) {
                body = body.substring(1);
            }
            this.text = body;
        }

        List<CsvRecord> parse() {
            List<CsvRecord> records = new ArrayList<>();
            while (pos < text.length()) {
                records.add(parseRecord());
            }
            return records;
        }

        private CsvRecord parseRecord() {
            int recordStartLine = line;
            List<String> fields = new ArrayList<>();
            while (true) {
                fields.add(parseField());
                if (pos >= text.length()) {
                    break;
                }
                char c = text.charAt(pos);
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == '\r' || c == '\n') {
                    consumeNewline();
                    break;
                }
                // parseField 已保证字段停在 , \r \n 或文件尾，不会走到这里
                throw new CsvFormatException("第 " + line + " 行：字段后存在非法字符");
            }
            return new CsvRecord(fields, recordStartLine);
        }

        private String parseField() {
            if (pos < text.length() && text.charAt(pos) == '"') {
                return parseQuotedField();
            }
            StringBuilder sb = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ',' || c == '\r' || c == '\n') {
                    break;
                }
                sb.append(c);
                pos++;
            }
            return sb.toString();
        }

        private String parseQuotedField() {
            int quoteLine = line;
            pos++; // 消耗开引号
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= text.length()) {
                    throw new CsvFormatException("第 " + quoteLine + " 行：引号字段未闭合");
                }
                char c = text.charAt(pos);
                if (c == '"') {
                    if (pos + 1 < text.length() && text.charAt(pos + 1) == '"') {
                        sb.append('"');
                        pos += 2;
                        continue;
                    }
                    pos++; // 消耗闭引号
                    break;
                }
                if (c == '\r' || c == '\n') {
                    consumeNewlineInto(sb);
                    continue;
                }
                sb.append(c);
                pos++;
            }
            // 闭引号之后只允许逗号、行尾或文件尾
            if (pos < text.length()) {
                char next = text.charAt(pos);
                if (next != ',' && next != '\r' && next != '\n') {
                    throw new CsvFormatException("第 " + line + " 行：引号字段后存在非法字符 '" + next + "'");
                }
            }
            return sb.toString();
        }

        /** 消耗一个行尾（CRLF / LF / CR），物理行号 +1。 */
        private void consumeNewline() {
            if (text.charAt(pos) == '\r' && pos + 1 < text.length() && text.charAt(pos + 1) == '\n') {
                pos += 2;
            } else {
                pos++;
            }
            line++;
        }

        /** 引号字段内的换行：原样保留进字段值，同时推进物理行号。 */
        private void consumeNewlineInto(StringBuilder sb) {
            if (text.charAt(pos) == '\r' && pos + 1 < text.length() && text.charAt(pos + 1) == '\n') {
                sb.append("\r\n");
                pos += 2;
            } else {
                sb.append(text.charAt(pos));
                pos++;
            }
            line++;
        }
    }
}
