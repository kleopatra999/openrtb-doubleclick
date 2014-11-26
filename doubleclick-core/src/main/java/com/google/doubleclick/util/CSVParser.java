/*
 * Copyright 2014 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.doubleclick.util;

import com.google.common.base.MoreObjects;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

/**
 * CSV (comma-separated) and TSV (tab-separated) parser for internal use only.
 * Remove this if we find some alternative that's small, bug-free / well-maintained,
 * and has all required features (including some extensions we need).
 * <p>
 * This parser is "record-oriented", it doesn't try to split a stream into records so this
 * will be done by the caller before invoking the parser. Unfortunately RFC-4180 supports
 * unescaped line breaks inside quoted fields, so a naive caller that just splits the stream
 * into records by looking at line breaks will fail to preserve the "internal line breaks".
 * In principle the caller can have the intelligence to split records correctly, but this
 * would ideally be implemented as part of the parser with a stream-oriented API.
 */
class CSVParser {
  private static final char EOT = (char) 0x03;
  static final char NUL = (char) 0x00;
  final char separator;
  final char quote;
  final char escape;
  final String empty;
  final boolean trim;
  final boolean singleQuote;

  /**
   * Creates a CSV parser (or TSV, but let's not get picky about naming).
   *
   * @param separator Separator. Normally comma (',' / 0x2C) for CSV, or tab ('\t' / 0x09) for TSV.
   * @param quote Quote. Normally the double-quote ('"', 0x22).
   * @param escape Escape. Non-RFC extension, allows escaping individual characters inside quoted
   * or unquoted fields. Defaults to NUL (no support for escaping), a popular choice would be '\'.
   * @param empty Empty value. Any absent field will be replaced by this value. Only a zero-char
   * field is considered absent; a quoted empty field ("") is not, so you can differentiate
   * between "no value at all" and "empty string value". The normal value for RFC-compliant CSV
   * or TSP parsing is the empty string, which causes no distinction between empty and zero-length.
   * @param trim If {@code true}, trims whitespaces in the start or end of all fields.
   * @param singleQuote If {@code true}, allows quoted fields to contain unquoted internal quotes,
   * e.g. ["My name is "John""]. In this case the parser detects the last, "external" quote when
   * the following character is the separator or end of line. One side effect of this format is
   * that internal quotes cannot be followed by a separator (unless the separator is escaped...).
   */
  public CSVParser(char separator, char quote, char escape, @Nullable String empty,
      boolean trim, boolean singleQuote) {
    this.separator = separator;
    this.quote = quote;
    this.escape = escape;
    this.empty = empty;
    this.trim = trim;
    this.singleQuote = singleQuote;
  }

  /**
   * Returns a RFC 4180-compliant CSV parser.
   */
  public static CSVParser csvParser() {
    return new CSVParser(',', '"', NUL, "", false, false);
  }

  /**
   * Returns an IANA-standard TSV parser.
   */
  public static CSVParser tsvParser() {
    return new CSVParser('\t', NUL, NUL, "", false, false);
  }

  /**
   * Parses one line / record.
   */
  public List<String> parse(String line) throws ParseException {
    List<String> cols = new ArrayList<>();
    boolean afterQuote = false;
    boolean afterEscape = false;
    boolean afterSeparator = false;
    boolean outerQuote = false;
    StringBuilder sb = new StringBuilder();

    for (int i = 0; ; ++i) {
      char c = (i == line.length()) ? EOT : line.charAt(i);
      if (afterEscape) {
        if (c == EOT) {
          // [abc\^]
          throw new ParseException("Escape not followed by a character", i);
        } else {
          // [abc\x...] => abcx...
          afterEscape = false;
          sb.append(c);
        }
      } else if (c == separator) {
        if (outerQuote && !afterQuote) {
          // ["abc,...] => abc,...
          sb.append(c);
        } else {
          // [abc,...] => {abc, ...}
          endCol(cols, sb, i, outerQuote, afterQuote);
          afterQuote = afterEscape = outerQuote = false;
          afterSeparator = true;
        }
      } else if (c == EOT) {
        if (sb.length() != 0 || afterSeparator || outerQuote) {
          // [...,abc^] => {..., abc}
          // [...,^] => {..., ""}
          endCol(cols, sb, i, outerQuote, afterQuote);
        }
        return cols;
      } else if (c == escape) {
        // [...\...]
        afterEscape = true;
      } else if (c == quote) {
        if (afterQuote && outerQuote && singleQuote) {
          // Two consecutive quotes inside quoted string, but in single-quote mode and
          // we don't yet know if the second quote is finishing the field or is also internal.
          // Print to output the first quote, but keep the second hanging (afterQuote set).
          sb.append(quote);
        } else if (afterQuote && outerQuote && !singleQuote) {
          // Two consecutive quotes inside quoted string, but in RFC mode (not single-quote)
          // so the pair has to be internal (the second quote cannot be terminating the field).
          // Consume both quotes producing a single output quote.
          sb.append(quote);
          afterQuote = false;
        } else if (sb.length() == 0 && !outerQuote) {
          outerQuote = true;
        } else if (sb.length() != 0 && !outerQuote) {
          // Fields that are not quote-delimited cannot have any internal quotes,
          // unless they are escaped which was already handled.
          throw new ParseException(escape == NUL
              ? "Unescaped quote inside non-quote-delimited field"
              : "Quote inside non-quote-delimited field",
              i);
        } else {
          afterQuote = true;
        }
      } else {
        // Common character.
        if (afterQuote) {
          // This will only happen when outerQuote && singleQuote
          sb.append(quote);
          afterQuote = false;
        }
        sb.append(c);
      }
    }
  }

  protected void endCol(
      List<String> cols, StringBuilder sb, int i, boolean outerQuote, boolean afterQuote)
      throws ParseException {
    if (outerQuote && !afterQuote) {
      throw new ParseException("Field starts with quote but ends unquoted", i);
    }
    // Drop trailing whitespace, if any; like: ["xyz"   ,] or [xyz   ,]
    cols.add(sb.length() == 0 && !outerQuote
        ? empty
        : trim ? trim(sb) : sb.toString());
    sb.setLength(0);
  }

  private static String trim(StringBuilder sb) {
    int first = 0;
    int last = sb.length() - 1;
    while ((first <= last) && (sb.charAt(first) <= ' ')) {
        ++first;
    }
    while ((first <= last) && (sb.charAt(last) <= ' ')) {
        --last;
    }
    return sb.substring(first,  last + 1);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).omitNullValues()
        .add("separator", separator == NUL ? null : "0x" + Integer.toHexString(separator))
        .add("quote", quote == NUL ? null : "0x" + Integer.toHexString(quote))
        .add("escape", escape == NUL ? null : "0x" + Integer.toHexString(escape))
        .add("empty", empty)
        .add("trim", trim)
        .add("singleQuote", singleQuote)
        .toString();
  }
}
