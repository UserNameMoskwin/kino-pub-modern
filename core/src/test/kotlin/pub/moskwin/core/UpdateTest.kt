package pub.moskwin.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateTest {
    @Test fun `version file is parsed`() {
        assertEquals(
            AppVersion(7, "0.7", "Что нового"),
            parseAppVersion("""{ "versionCode": 7, "versionName": "0.7", "notes": "Что нового", "extra": 1 }"""),
        )
        assertEquals(AppVersion(3), parseAppVersion("""{"versionCode":3}"""))
    }

    @Test fun `broken version file gives nothing`() {
        assertNull(parseAppVersion(""))
        assertNull(parseAppVersion("<html>404</html>"))
        assertNull(parseAppVersion("""{"versionName":"0.7"}"""))
        assertNull(parseAppVersion("""{"versionCode":0}"""))
    }
}
