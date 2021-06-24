/*
 * Copyright (c) 2011-2021 VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.transport.logging;

import java.nio.charset.Charset;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.logging.ByteBufFormat;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import reactor.netty.ChannelOperationsId;
import reactor.netty.Connection;

import static io.netty.buffer.ByteBufUtil.appendPrettyHexDump;
import static io.netty.util.internal.StringUtil.NEWLINE;
import static java.util.Objects.requireNonNull;
import static reactor.netty.transport.logging.AdvancedByteBufFormat.HEX_DUMP;
import static reactor.netty.transport.logging.AdvancedByteBufFormat.SIMPLE;
import static reactor.netty.transport.logging.AdvancedByteBufFormat.TEXTUAL;

/**
 * Extends {@link LoggingHandler} in order to provide extended connection id, which in case of HTTP
 * adds the serial number of the request received on that connection.
 * <p>
 * In case of {@link AdvancedByteBufFormat#TEXTUAL} logs all events in a
 * textual representation so it's human readable and less verbose.
 * <p>
 * Hint: Logger escapes newlines as "\n" to reduce output.
 *
 * @author Maximilian Goeke
 * @author Violeta Georgieva
 * @since 1.0.0
 */
final class ReactorNettyLoggingHandler extends LoggingHandler {

	private final AdvancedByteBufFormat byteBufFormat;
	private final Charset charset;

	/**
	 * Creates a new instance with the specified logger name, level and byte buffer format.
	 *
	 * @param name          the name of the class to use for the logger
	 * @param level         the log level
	 * @param byteBufFormat the byte buffer format
	 */
	ReactorNettyLoggingHandler(String name, LogLevel level, AdvancedByteBufFormat byteBufFormat) {
		super(name, level, byteBufFormat == SIMPLE ? ByteBufFormat.SIMPLE : ByteBufFormat.HEX_DUMP);
		this.charset = null;
		this.byteBufFormat = byteBufFormat;
	}

	/**
	 * Creates a new instance with the specified logger name, level and charset.
	 * The byte buffer format is {@link AdvancedByteBufFormat#TEXTUAL}
	 *
	 * @param name    the name of the class to use for the logger
	 * @param level   the log level
	 * @param charset the charset used to decode the ByteBuf
	 */
	ReactorNettyLoggingHandler(final String name, final LogLevel level, final Charset charset) {
		super(name, level);
		this.byteBufFormat = TEXTUAL;
		this.charset = requireNonNull(charset, "charset");
	}

	/*
	 * The UnsupportedOperationException is thrown to reduce confusion. ReactorNettyLoggingHandler is using
	 * the AdvancedByteBufFormat and not ByteBufFormat.
	 */
	@Override
	public ByteBufFormat byteBufFormat() {
		if (byteBufFormat == SIMPLE) {
			return ByteBufFormat.SIMPLE;
		}
		else if (byteBufFormat == HEX_DUMP) {
			return ByteBufFormat.HEX_DUMP;
		}
		throw new UnsupportedOperationException("ReactorNettyLoggingHandler isn't using the classic ByteBufFormat.");
	}

	@Override
	protected String format(ChannelHandlerContext ctx, String eventName) {
		String chStr = channelString(ctx.channel());
		return new StringBuilder(chStr.length() + 1 + eventName.length())
				.append(chStr)
				.append(' ')
				.append(eventName)
				.toString();
	}

	@Override
	protected String format(ChannelHandlerContext ctx, String eventName, Object arg) {
		if (arg instanceof ByteBuf) {
			return formatByteBuf(ctx, eventName, (ByteBuf) arg);
		}
		else if (arg instanceof ByteBufHolder) {
			return formatByteBufHolder(ctx, eventName, (ByteBufHolder) arg);
		}
		else {
			return formatSimple(ctx, eventName, arg);
		}
	}

	@Override
	protected String format(ChannelHandlerContext ctx, String eventName, Object firstArg, Object secondArg) {
		if (secondArg == null) {
			return formatSimple(ctx, eventName, firstArg);
		}

		String chStr = channelString(ctx.channel());
		String arg1Str = String.valueOf(firstArg);
		String arg2Str = secondArg.toString();
		return new StringBuilder(chStr.length() + 1 + eventName.length() + 2 + arg1Str.length() + 2 + arg2Str.length())
				.append(chStr)
				.append(' ')
				.append(eventName)
				.append(": ")
				.append(arg1Str)
				.append(", ")
				.append(arg2Str)
				.toString();
	}

	private String channelString(Channel channel) {
		Connection connection = Connection.from(channel);
		if (connection instanceof ChannelOperationsId) {
			String channelStr = ((ChannelOperationsId) connection).asLongText();
			return new StringBuilder(4 + channelStr.length() + 1)
					.append(CHANNEL_ID_PREFIX)
					.append(channelStr)
					.append(CHANNEL_ID_SUFFIX)
					.toString();
		}
		else {
			// Replace "[id: 0x" with "[id:" in order to keep it consistent with ChannelOperationsId#asLongText()
			String channelStr = channel.toString().substring(ORIGINAL_CHANNEL_ID_PREFIX_LENGTH);
			return new StringBuilder(4 + channelStr.length())
					.append(CHANNEL_ID_PREFIX)
					.append(channelStr)
					.toString();
		}
	}

	private String formatByteBuf(ChannelHandlerContext ctx, String eventName, ByteBuf msg) {
		String chStr = channelString(ctx.channel());
		int length = msg.readableBytes();
		if (length == 0) {
			return new StringBuilder(chStr.length() + 1 + eventName.length() + 4)
					.append(chStr)
					.append(' ')
					.append(eventName)
					.append(": 0B")
					.toString();
		}
		else {
			int outputLength = chStr.length() + 1 + eventName.length() + 2 + 10 + 1;
			String message = "";
			if (byteBufFormat == HEX_DUMP) {
				int rows = length / 16 + (length % 15 == 0 ? 0 : 1) + 4;
				int hexDumpLength = 2 + rows * 80;
				outputLength += hexDumpLength;
			}
			else if (byteBufFormat == TEXTUAL) {
				message = msg.toString(charset);
				outputLength += message.length() + 1;
			}
			StringBuilder buf = new StringBuilder(outputLength)
					.append(chStr)
					.append(' ')
					.append(eventName)
					.append(": ")
					.append(length)
					.append('B');
			if (byteBufFormat == HEX_DUMP) {
				buf.append(NEWLINE);
				appendPrettyHexDump(buf, msg);
			}
			else if (byteBufFormat == TEXTUAL) {
				buf.append(' ').append(message);
			}

			return buf.toString();
		}
	}

	private String formatByteBufHolder(ChannelHandlerContext ctx, String eventName, ByteBufHolder msg) {
		String chStr = channelString(ctx.channel());
		String msgStr = msg.toString();
		ByteBuf content = msg.content();
		int length = content.readableBytes();
		if (length == 0) {
			return new StringBuilder(chStr.length() + 1 + eventName.length() + 2 + msgStr.length() + 4)
					.append(chStr)
					.append(' ')
					.append(eventName)
					.append(", ")
					.append(msgStr)
					.append(", 0B")
					.toString();
		}
		else {
			StringBuilder buf;
			if (byteBufFormat != TEXTUAL) {
				int outputLength = chStr.length() + 1 + eventName.length() + 2 + msgStr.length() + 2 + 10 + 1;
				if (byteBufFormat == HEX_DUMP) {
					int rows = length / 16 + (length % 15 == 0 ? 0 : 1) + 4;
					int hexDumpLength = 2 + rows * 80;
					outputLength += hexDumpLength;
				}
				buf = new StringBuilder(outputLength)
						.append(chStr)
						.append(' ')
						.append(eventName)
						.append(": ")
						.append(msgStr)
						.append(", ")
						.append(length)
						.append('B');
				if (byteBufFormat == HEX_DUMP) {
					buf.append(NEWLINE);
					appendPrettyHexDump(buf, content);
				}
			}
			else {
				String message = content.toString(charset);
				int outputLength = chStr.length() + 1 + eventName.length() + 2 + 10 + 2 + message.length();
				buf = new StringBuilder(outputLength)
						.append(chStr)
						.append(' ')
						.append(eventName)
						.append(": ")
						.append(length)
						.append("B ")
						.append(message);
			}

			return buf.toString();
		}
	}

	private String formatSimple(ChannelHandlerContext ctx, String eventName, Object msg) {
		String chStr = channelString(ctx.channel());
		String msgStr = String.valueOf(msg);
		return new StringBuilder(chStr.length() + 1 + eventName.length() + 2 + msgStr.length())
				.append(chStr)
				.append(' ')
				.append(eventName)
				.append(": ")
				.append(msgStr)
				.toString();
	}

	static final int ORIGINAL_CHANNEL_ID_PREFIX_LENGTH = "[id: 0x".length();
	static final String CHANNEL_ID_PREFIX = "[id:";
	static final char CHANNEL_ID_SUFFIX = ']';
}
