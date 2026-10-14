package io.github.c4software.gliff.data.local

import io.github.c4software.gliff.domain.machine.Machine
import io.github.c4software.gliff.domain.settings.Settings
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

class StoredRepositoriesTest {
    @Test
    fun theMachinesArePersistedOnEveryChange() {
        val store = MemoryStore()
        val repo = StoredMachineRepository(store)
        val a = Machine(user = "val", host = "a")
        val b = Machine(user = "val", host = "b", port = 2222, headless = false, output = "DP-1")
        repo.save(a)
        repo.save(b)
        repo.save(a.copy(hostKey = "SHA256:abc"))
        assertEquals(listOf(a.copy(hostKey = "SHA256:abc"), b), StoredMachineRepository(store).machines.value)
        repo.remove(a.id)
        assertEquals(listOf(b), StoredMachineRepository(store).machines.value)
        assertEquals(b, repo.find(b.id))
        assertNull(repo.find(a.id))
    }

    @Test
    fun unreadableMachinesStartEmptyAndUnknownFieldsAreIgnored() {
        val store = MemoryStore()
        store.set("machines", "not json")
        assertEquals(emptyList(), StoredMachineRepository(store).machines.value)
        store.set("machines", """[{"id":"1","user":"val","host":"box","addedLater":true}]""")
        assertEquals(listOf(Machine(id = "1", user = "val", host = "box")), StoredMachineRepository(store).machines.value)
    }

    @Test
    fun theMachinesAreWrittenAsEarlierVersionsWroteThem() {
        val store = MemoryStore()
        StoredMachineRepository(store).save(
            Machine(id = "1", user = "val", host = "box", port = 2222, headless = false, output = "DP-1", hostKey = "SHA256:abc")
        )
        assertEquals(
            """[{"id":"1","user":"val","host":"box","port":2222,"headless":false,"output":"DP-1","hostKey":"SHA256:abc"}]""",
            store.values["machines"],
        )
        // Saved by 1.0, with every field.
        store.set(
            "machines",
            """[{"id":"2","user":"root","host":"tour","port":22,"headless":true,"output":"","serverBin":"/opt/gliff-server",""" +
                """"forcedWidth":1920,"forcedHeight":1080,"hostKey":"SHA256:xyz"}]""",
        )
        assertEquals(
            listOf(Machine("2", "root", "tour", 22, true, "", "/opt/gliff-server", 1920, 1080, "SHA256:xyz")),
            StoredMachineRepository(store).machines.value,
        )
    }

    @Test
    fun settingsAreKeptAndSurviveNewFields() {
        val store = MemoryStore()
        assertEquals(Settings(), StoredSettingsRepository(store).settings.value)
        StoredSettingsRepository(store).update { it.copy(forcedWidth = 1600, forcedHeight = 900, httpMirror = true, mirrorToken = "abc") }
        assertEquals(
            """{"forcedWidth":1600,"forcedHeight":900,"httpMirror":true,"mirrorToken":"abc"}""",
            store.values["settings"],
        )
        assertEquals(
            Settings(forcedWidth = 1600, forcedHeight = 900, httpMirror = true, mirrorToken = "abc"),
            StoredSettingsRepository(store).settings.value,
        )
        // Written by another version of the app: what is known is kept, the rest ignored.
        store.set("settings", """{"forcedWidth":1280,"forcedHeight":720,"scale":2.0,"somethingNew":1}""")
        assertEquals(Settings(forcedWidth = 1280, forcedHeight = 720), StoredSettingsRepository(store).settings.value)
        store.set("settings", "not json")
        assertEquals(Settings(), StoredSettingsRepository(store).settings.value)
    }
}
