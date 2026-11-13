package dev.suanniao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 检查更新看哪个仓库 */
class UpdateSourceTest {
    @Test fun defaultBuildChecksTheAuthorsReleasesGiteeFirst() {
        // 交给作者的代码不能带别人的仓库名：代码里的默认值必须是作者的（本机打开发版时用 -PupdateRepo 换掉，不影响这里）
        assertEquals("drhhhao/SuanNIAO", UpdateSource.DEFAULT_REPO)
        assertFalse(UpdateSource.custom(UpdateSource.DEFAULT_REPO))
        assertEquals(listOf(
            "https://gitee.com/drhhhao_0/SuanNIAO/releases/download/latest/latest.json",
            "https://github.com/drhhhao/SuanNIAO/releases/latest/download/latest.json",
        ), UpdateSource.manifestUrls("drhhhao/SuanNIAO"))
    }

    @Test fun devBuildOnlyChecksItsOwnGithubReleases() {
        assertTrue(UpdateSource.custom("someone/SuanNIAO"))
        assertEquals(listOf("https://github.com/someone/SuanNIAO/releases/latest/download/latest.json"),
            UpdateSource.manifestUrls("someone/SuanNIAO"))
        assertEquals("https://github.com/someone/SuanNIAO/releases/latest", UpdateSource.releasesPage("someone/SuanNIAO"))
    }
}
