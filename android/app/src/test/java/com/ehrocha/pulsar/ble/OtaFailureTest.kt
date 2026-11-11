/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class OtaFailureTest {
    @Test fun firmwareStatusesMapToTheirOwnFailures() {
        assertEquals(OtaFailure.WRONG_BOARD, failureFor(OtaStatus.ERR_BOARD))
        assertEquals(OtaFailure.BUSY, failureFor(OtaStatus.ERR_BUSY))
        assertEquals(OtaFailure.BEGIN_REJECTED, failureFor(OtaStatus.ERR_BEGIN))
        assertEquals(OtaFailure.WRITE_FAILED, failureFor(OtaStatus.ERR_WRITE))
        assertEquals(OtaFailure.VALIDATE_FAILED, failureFor(OtaStatus.ERR_VALIDATE))
        assertEquals(OtaFailure.TOO_LARGE, failureFor(OtaStatus.ERR_SIZE))
        assertEquals(OtaFailure.UNKNOWN_STATUS, failureFor(OtaStatus.UNKNOWN))
    }
}
