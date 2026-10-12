package io.github.c4software.gliff.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MemoryStore : KeyValueStore {
    val values = HashMap<String, String>()
    override fun get(key: String): String? = values[key]
    override fun set(key: String, value: String) {
        values[key] = value
    }
}

class MachineTest {
    @Test
    fun addressesParse() {
        assertEquals(Triple("val", "box", 22), Machine.parseAddress("val@box"))
        assertEquals(Triple("val", "10.0.0.2", 2222), Machine.parseAddress("  val@10.0.0.2:2222 "))
        assertEquals(Triple("val", "box.tail1234.ts.net", 22), Machine.parseAddress("val@box.tail1234.ts.net"))
        // A bare IPv6 address has colons but no port.
        assertEquals(Triple("val", "fd7a::1", 22), Machine.parseAddress("val@fd7a::1"))
        for (bad in listOf("", "box", "@box", "val@", "val@box:", "val@box:0", "val@box:99999", "val@box:ssh", "a@b@c", "val@my box")) {
            assertNull(Machine.parseAddress(bad), bad)
        }
    }

    @Test
    fun addressShowsOnlyAnUnusualPort() {
        assertEquals("val@box", Machine(user = "val", host = "box").address)
        assertEquals("val@box:2222", Machine(user = "val", host = "box", port = 2222).address)
    }

    @Test
    fun serverArgumentsFollowTheReferenceClient() {
        val m = Machine(user = "val", host = "box")
        assertEquals(listOf("--headless"), m.serverArgs)
        assertEquals(listOf("--output", "auto"), m.copy(headless = false).serverArgs)
        assertEquals(listOf("--output", "DP-1"), m.copy(headless = false, output = " DP-1 ").serverArgs)
        // The output name is ignored for a private screen.
        assertEquals(listOf("--headless"), m.copy(output = "DP-1").serverArgs)
    }

    @Test
    fun theRepositoryPersistsEveryChange() {
        val store = MemoryStore()
        val repo = MachineRepository(store)
        val a = Machine(user = "val", host = "a")
        val b = Machine(user = "val", host = "b", port = 2222, headless = false, output = "DP-1")
        repo.save(a)
        repo.save(b)
        repo.save(a.copy(hostKey = "SHA256:abc"))
        assertEquals(listOf(a.copy(hostKey = "SHA256:abc"), b), MachineRepository(store).machines.value)
        repo.remove(a.id)
        assertEquals(listOf(b), MachineRepository(store).machines.value)
        assertEquals(b, repo.find(b.id))
        assertNull(repo.find(a.id))
    }

    @Test
    fun unreadableSettingsStartEmptyAndUnknownFieldsAreIgnored() {
        val store = MemoryStore()
        store.set("machines", "not json")
        assertEquals(emptyList(), MachineRepository(store).machines.value)
        store.set("machines", """[{"id":"1","user":"val","host":"box","addedLater":true}]""")
        assertEquals(listOf(Machine(id = "1", user = "val", host = "box")), MachineRepository(store).machines.value)
    }

    @Test
    fun settingsAreKeptAndSurviveNewFields() {
        val store = MemoryStore()
        assertEquals(Settings(), SettingsRepository(store).settings.value)
        SettingsRepository(store).update { it.copy(scale = 1.5f, httpMirror = true, mirrorToken = "abc") }
        assertEquals(Settings(scale = 1.5f, httpMirror = true, mirrorToken = "abc"), SettingsRepository(store).settings.value)
        // Written by another version of the app: what is known is kept, the rest ignored.
        store.set("settings", """{"scale":2.0,"somethingNew":1}""")
        assertEquals(Settings(scale = 2f), SettingsRepository(store).settings.value)
        store.set("settings", "not json")
        assertEquals(Settings(), SettingsRepository(store).settings.value)
    }
}
