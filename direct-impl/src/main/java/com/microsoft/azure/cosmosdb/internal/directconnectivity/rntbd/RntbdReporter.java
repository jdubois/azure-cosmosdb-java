/*
 * The MIT License (MIT)
 * Copyright (c) 2018 Microsoft Corporation
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */

package com.microsoft.azure.cosmosdb.internal.directconnectivity.rntbd;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.MessageFormatter;

import java.io.File;
import java.net.URL;

public final class RntbdReporter {

    private static final String codeSource;

    static {
        String value;
        try {
            URL url = RntbdReporter.class.getProtectionDomain().getCodeSource().getLocation();
            File file = new File(url.toURI());
            value = file.getName();
        } catch (Throwable error) {
            value = "azure-cosmosdb-direct";
        }
        codeSource = value;
    }

    private RntbdReporter() {
    }

    public static void reportIssue(Logger logger, Object subject, String format, Object... arguments) {
        if (logger.isErrorEnabled()) {
            doReportIssue(logger, subject, format, arguments);
        }
    }

    public static void reportIssueUnless(
        boolean predicate, Logger logger, Object subject, String format, Object... arguments
    ) {
        if (!predicate && logger.isErrorEnabled()) {
            doReportIssue(logger, subject, format, arguments);
        }
    }

    private static void doReportIssue(Logger logger, Object subject, String format, Object[] arguments) {

        FormattingTuple formattingTuple = MessageFormatter.arrayFormat(format, arguments);
        StackTraceElement[] stackTraceElements = new Exception().getStackTrace();
        Throwable throwable = formattingTuple.getThrowable();

        if (throwable == null) {
            logger.error("Report this {} issue to ensure it is addressed:\n[{}]\n[{}]\n[{}]",
                codeSource, subject, stackTraceElements[2], formattingTuple.getMessage()
            );
        } else {
            logger.error("Report this {} issue to ensure it is addressed:\n[{}]\n[{}]\n[{}{}{}]",
                codeSource, subject, stackTraceElements[2], formattingTuple.getMessage(),
                throwable, ExceptionUtils.getStackTrace(throwable)
            );
        }
    }
}
