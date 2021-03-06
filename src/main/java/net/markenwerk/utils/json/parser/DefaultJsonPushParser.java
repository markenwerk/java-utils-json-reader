/*
 * Copyright (c) 2016 Torsten Krause, Markenwerk GmbH
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package net.markenwerk.utils.json.parser;

import java.io.IOException;
import java.io.Reader;
import java.util.Arrays;
import java.util.List;

import net.markenwerk.utils.json.common.JsonException;
import net.markenwerk.utils.json.handler.JsonHandler;

/**
 * A {@link DefaultJsonPushParser} is a stream based JSON parser. It reads
 * characters from a given {@link Reader} as far as necessary to calculate a
 * {@link JsonState} or to yield the next value.
 *
 * @author Torsten Krause (tk at markenwerk dot net)
 * @since 1.0.0
 */
public final class DefaultJsonPushParser implements JsonPushParser {

	private final StringBuilder builder = new StringBuilder();

	private final JsonSource source;

	private final boolean multiDocumentMode;

	private final boolean strictStructMode;

	private JsonHandler<?> handler;

	/**
	 * Creates a new {@link DefaultJsonPushParser}.
	 *
	 * @param string
	 *            The {@link String} to read from.
	 * 
	 * @throws IllegalArgumentException
	 *             If the given {@link String} is {@literal null}.
	 */
	public DefaultJsonPushParser(String string) throws IllegalArgumentException {
		this(new StringJsonSource(string));
	}

	/**
	 * Creates a new {@link DefaultJsonPushParser}.
	 *
	 * @param characters
	 *            The {@code char[]} to read from.
	 * 
	 * @throws IllegalArgumentException
	 *             If the given {@code char[]} is {@literal null}.
	 */
	public DefaultJsonPushParser(char[] characters) throws IllegalArgumentException {
		this(new CharacterArrayJsonSource(characters));
	}

	/**
	 * Creates a new {@link DefaultJsonPushParser}.
	 * 
	 * @param reader
	 *            The {@link Reader} to read from.
	 * 
	 * @throws IllegalArgumentException
	 *             If the given {@link Reader} is {@literal null}.
	 */
	public DefaultJsonPushParser(Reader reader) throws IllegalArgumentException {
		this(new ReaderJsonSource(reader));
	}

	/**
	 * Creates a new {@link DefaultJsonPushParser}.
	 * 
	 * @param source
	 *            The {@link JsonSource} to read from.
	 * 
	 * @throws IllegalArgumentException
	 *             If the given {@link JsonSource} is {@literal null}.
	 */
	public DefaultJsonPushParser(JsonSource source) throws IllegalArgumentException {
		this(source, (JsonParserMode[]) null);
	}

	/**
	 * Creates a new {@link DefaultJsonPushParser}.
	 * 
	 * @param source
	 *            The {@link JsonSource} to read from.
	 * @param modes
	 *            Selection of {@link JsonParserMode JsonParserModes} to be used
	 *            during parsing.
	 * 
	 * @throws IllegalArgumentException
	 *             If the given {@link JsonSource} is {@literal null}.
	 */
	public DefaultJsonPushParser(JsonSource source, JsonParserMode... modes) throws IllegalArgumentException {
		if (null == source) {
			throw new IllegalArgumentException("The given source is null");
		}
		this.source = source;
		if (null != modes) {
			List<JsonParserMode> modesList = Arrays.asList(modes);
			this.multiDocumentMode = modesList.contains(JsonParserMode.MULTI_DOCUMENT_MODE);
			this.strictStructMode = modesList.contains(JsonParserMode.STRICT_STRUCT_MODE);
		} else {
			this.multiDocumentMode = false;
			this.strictStructMode = false;
		}
	}

	@Override
	public <Result> Result handle(JsonHandler<Result> handler) throws IllegalArgumentException,
			JsonException, JsonSyntaxException, IOException {
		if (null == handler) {
			throw new IllegalArgumentException("The given handler is null");
		}
		try {
			this.handler = handler;
			handleDocument();
			return handler.getResult();
		} finally {
			close();
		}
	}

	private void handleDocument() throws JsonSyntaxException, JsonException, IOException {
		while (true) {
			handler.onDocumentBegin();
			char firstCharacter = nextNonWhitespace(JsonSyntaxError.INVALID_DOCUMENT_START);
			if ('{' == firstCharacter) {
				handleObjectFirst();
			} else if ('[' == firstCharacter) {
				handleArrayFirst();
			} else {
				if (strictStructMode) {
					throw syntaxError(JsonSyntaxError.INVALID_DOCUMENT_START);
				} else if ('"' == firstCharacter) {
					handler.onString(readNextString());
				} else {
					handleLiteral(firstCharacter);
				}
			}
			handler.onDocumentEnd();
			if (hasNextNonWhitespace()) {
				if (!multiDocumentMode) {
					throw syntaxError(JsonSyntaxError.INVALID_DOCUMENT_END);
				}
			} else {
				break;
			}
		}
	}

	private void handleArrayFirst() throws JsonSyntaxException, JsonException, IOException {
		handler.onArrayBegin();
		char nextCharacter = nextNonWhitespace(JsonSyntaxError.INVALID_ARRAY_FIRST);
		if (']' == nextCharacter) {
			handler.onArrayEnd();
		} else {
			handleValue(nextCharacter, JsonSyntaxError.INVALID_ARRAY_FIRST);
			handleArrayFollowing();
		}
	}

	private void handleArrayFollowing() throws JsonSyntaxException, JsonException, IOException {
		while (true) {
			char nextCharacter = nextNonWhitespace(JsonSyntaxError.INVALID_ARRAY_FOLLOW);
			if (']' == nextCharacter) {
				handler.onArrayEnd();
				break;
			} else if (',' == nextCharacter) {
				handler.onNext();
				handleValue(JsonSyntaxError.INVALID_ARRAY_VALUE);
			} else {
				throw syntaxError(JsonSyntaxError.INVALID_ARRAY_FOLLOW);
			}
		}
	}

	private void handleObjectFirst() throws JsonSyntaxException, JsonException, IOException {
		handler.onObjectBegin();
		char nextCharacter = nextNonWhitespace(JsonSyntaxError.INVALID_OBJECT_FIRST);
		if ('}' == nextCharacter) {
			handler.onObjectEnd();
		} else if ('"' == nextCharacter) {
			handleObjectValue();
			handleObjectFollowing();
		} else {
			throw syntaxError(JsonSyntaxError.INVALID_OBJECT_FIRST);
		}
	}

	private void handleObjectFollowing() throws JsonSyntaxException, JsonException, IOException {
		while (true) {
			char nextCharacter = nextNonWhitespace(JsonSyntaxError.INVALID_OBJECT_FOLLOW);
			if ('}' == nextCharacter) {
				handler.onObjectEnd();
				break;
			} else if (',' == nextCharacter) {
				nextCharacter = nextNonWhitespace(JsonSyntaxError.INVALID_OBJECT_NAME);
				if ('"' == nextCharacter) {
					handler.onNext();
					handleObjectValue();
				} else {
					throw syntaxError(JsonSyntaxError.INVALID_OBJECT_NAME);
				}
			} else {
				throw syntaxError(JsonSyntaxError.INVALID_OBJECT_FOLLOW);
			}
		}
	}

	private void handleObjectValue() throws JsonSyntaxException, JsonException, IOException {
		handler.onName(readNextString());
		char nextCharacter = nextNonWhitespace(JsonSyntaxError.INVALID_OBJECT_SEPARATION);
		if (':' == nextCharacter) {
			handleValue(JsonSyntaxError.INVALID_OBJECT_VALUE);
		} else {
			throw syntaxError(JsonSyntaxError.INVALID_OBJECT_SEPARATION);
		}
	}

	private void handleValue(JsonSyntaxError error) throws JsonSyntaxException, JsonException,
			IOException {
		handleValue(nextNonWhitespace(error), error);
	}

	private void handleValue(char firstCharacter, JsonSyntaxError error) throws JsonSyntaxException,
			JsonException, IOException {
		if ('{' == firstCharacter) {
			handleObjectFirst();
		} else if ('[' == firstCharacter) {
			handleArrayFirst();
		} else if ('"' == firstCharacter) {
			handler.onString(readNextString());
		} else if (']' == firstCharacter) {
			throw syntaxError(error);
		} else if ('}' == firstCharacter) {
			throw syntaxError(error);
		} else {
			handleLiteral(firstCharacter);
		}
	}

	private char nextNonWhitespace(JsonSyntaxError error) throws JsonSyntaxException, IOException {
		while (source.makeAvailable(1)) {
			for (int i = 0, n = source.getAvailable(); i < n; i++) {
				char nextCharacter = source.nextCharacter();
				if (' ' != nextCharacter && '\t' != nextCharacter && '\n' != nextCharacter && '\r' != nextCharacter) {
					return nextCharacter;
				}
			}
		}
		throw syntaxError(error);
	}

	private boolean hasNextNonWhitespace() throws JsonSyntaxException, IOException {
		while (0 != source.makeAvailable()) {
			for (int i = 0, n = source.getAvailable(); i < n; i++) {
				char nextCharacter = source.peekCharacter(0);
				if (' ' != nextCharacter && '\t' != nextCharacter && '\n' != nextCharacter && '\r' != nextCharacter) {
					return true;
				} else {
					source.nextCharacter();
				}
			}
		}
		return false;
	}

	private String readNextString() throws JsonSyntaxException, IOException {
		builder.setLength(0);
		boolean buffered = false;
		while (source.makeAvailable(1)) {
			int offset = 0;
			int available = source.getAvailable();
			while (offset < available) {
				char nextCharacter = source.peekCharacter(offset);
				if ('"' == nextCharacter) {
					String stringValue;
					if (buffered) {
						source.appendNextString(builder, offset);
						stringValue = builder.toString();
					} else {
						stringValue = source.nextString(offset);
					}
					source.nextCharacter();
					return stringValue;
				} else if ('\\' == nextCharacter) {
					buffered = true;
					source.appendNextString(builder, offset);
					source.nextCharacter();
					builder.append(readEscaped());
					offset = -1;
					break;
				}
				offset++;
			}
			if (-1 != offset) {
				buffered = true;
				source.appendNextString(builder, offset);
			}
		}
		throw syntaxError(JsonSyntaxError.UNTERMINATED_STRING);
	}

	private char readEscaped() throws JsonSyntaxException, IOException {
		if (!source.makeAvailable(1)) {
			throw syntaxError(JsonSyntaxError.UNFINISHED_ESCAPE_SEQUENCE);
		} else {
			switch (source.nextCharacter()) {
			case '"':
				return '"';
			case '\\':
				return '\\';
			case '/':
				return '/';
			case 'b':
				return '\b';
			case 'f':
				return '\f';
			case 'n':
				return '\n';
			case 'r':
				return '\r';
			case 't':
				return '\t';
			case 'u':
				return readUnicodeEscaped();
			default:
				throw syntaxError(JsonSyntaxError.INVALID_ESCAPE_SEQUENCE);
			}
		}
	}

	private char readUnicodeEscaped() throws JsonSyntaxException, IOException {
		if (!source.makeAvailable(4)) {
			throw syntaxError(JsonSyntaxError.UNFINISHED_UNICODE_ESCAPE_SEQUENCE);
		} else {
			String hex = source.nextString(4);
			try {
				return (char) Integer.parseInt(hex, 16);
			} catch (NumberFormatException e) {
				if (hex.contains("\"")) {
					throw syntaxError(JsonSyntaxError.UNFINISHED_UNICODE_ESCAPE_SEQUENCE);
				} else {
					throw syntaxError(JsonSyntaxError.INVALID_UNICODE_ESCAPE_SEQUENCE);
				}
			}
		}
	}

	private void handleLiteral(char firstCharacter) throws JsonSyntaxException, JsonException,
			IOException {
		builder.setLength(0);
		builder.append(firstCharacter);
		while (0 != source.makeAvailable()) {
			switch (source.peekCharacter(0)) {
			case ']':
			case '}':
			case ',':
			case ' ':
			case '\b':
			case '\f':
			case '\n':
			case '\r':
			case '\t':
				handleLiteral(builder.toString());
				return;
			default:
				builder.append(source.nextCharacter());
			}
		}
		handleLiteral(builder.toString());
	}

	private void handleLiteral(String literal) throws JsonSyntaxException, JsonException {
		if ("null".equals(literal)) {
			handler.onNull();
		} else if ("false".equals(literal)) {
			handler.onBoolean(false);
		} else if ("true".equals(literal)) {
			handler.onBoolean(true);
		} else {
			handleNumber(literal);
		}
	}

	private void handleNumber(String literal) throws JsonSyntaxException, JsonException {
		try {
			handler.onLong(Long.parseLong(literal));
		} catch (NumberFormatException ignored) {
			try {
				handler.onDouble(Double.parseDouble(literal));
			} catch (NumberFormatException e) {
				throw syntaxError(JsonSyntaxError.INVALID_LITERAL);
			}
		}
	}

	private JsonSyntaxException syntaxError(JsonSyntaxError error) {
		return new JsonSyntaxException(error, source.getLine(), source.getColumn() - 1, source.getPast(5),
				source.getFuture(5));
	}

	@Override
	public int getLine() {
		return source.getLine();
	}

	@Override
	public int getColumn() {
		return source.getColumn();
	}

	public void close() throws IOException {
		source.close();
	}

	@Override
	public String toString() {
		return "JsonReader [line=" + source.getLine() + ", column=" + source.getColumn() + ", near='"
				+ source.getPast(15) + source.getFuture(15) + "']";
	}

}
