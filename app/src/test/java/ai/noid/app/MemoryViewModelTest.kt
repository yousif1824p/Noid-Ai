package ai.noid.app

import android.os.Looper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class MemoryViewModelTest {
    @Test
    fun reportsSuccessfulSaveUpdateAndDelete() {
        val dao = FakeMemoryDao()
        val viewModel = MemoryViewModel(MemoryRepository(dao))

        viewModel.saveApprovedMemory("عنوان", "معلومة", "موافقة المستخدم")
        idleMainLooper()
        assertEquals("تم حفظ المعلومة في الذاكرة المحلية.", viewModel.operationMessage.value)
        assertEquals(1, dao.memories.value.size)
        assertFalse(viewModel.isOperating.value)

        val id = dao.memories.value.single().id
        viewModel.updateMemory(id, "عنوان محدث", "معلومة محدثة", "موافقة المستخدم")
        idleMainLooper()
        assertEquals("تم تحديث الذاكرة المحلية.", viewModel.operationMessage.value)
        assertEquals("عنوان محدث", dao.memories.value.single().title)

        viewModel.deleteMemory(id)
        idleMainLooper()
        assertEquals("تم حذف المعلومة من الذاكرة المحلية.", viewModel.operationMessage.value)
        assertTrue(dao.memories.value.isEmpty())
    }

    @Test
    fun reportsFailureWithoutLeakingDatabaseExceptionDetails() {
        val dao = FakeMemoryDao().apply { failInsert = true }
        val viewModel = MemoryViewModel(MemoryRepository(dao))

        viewModel.saveApprovedMemory("عنوان", "معلومة", "موافقة المستخدم")
        idleMainLooper()

        assertEquals(
            "تعذر حفظ المعلومة. لم يتم تأكيد نجاح العملية.",
            viewModel.operationMessage.value
        )
        assertFalse(viewModel.isOperating.value)
        assertTrue(dao.memories.value.isEmpty())
    }

    private fun idleMainLooper() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private class FakeMemoryDao : UserMemoryDao {
        val memories = MutableStateFlow<List<UserMemory>>(emptyList())
        private var nextId = 1L
        var failInsert = false

        override fun observeAll(): Flow<List<UserMemory>> = memories

        override suspend fun getAll(): List<UserMemory> = memories.value

        override suspend fun getRecent(limit: Int): List<UserMemory> =
            memories.value.sortedByDescending { it.updatedAt }.take(limit)

        override suspend fun search(query: String, limit: Int): List<UserMemory> =
            memories.value.filter {
                it.title.contains(query, ignoreCase = true) ||
                    it.content.contains(query, ignoreCase = true)
            }.take(limit)

        override suspend fun insert(memory: UserMemory): Long {
            if (failInsert) throw IllegalStateException("private database path")
            val id = nextId++
            memories.value = memories.value + memory.copy(id = id)
            return id
        }

        override suspend fun update(
            memoryId: Long,
            title: String,
            content: String,
            source: String,
            updatedAt: Long
        ) {
            memories.value = memories.value.map { memory ->
                if (memory.id == memoryId) {
                    memory.copy(
                        title = title,
                        content = content,
                        source = source,
                        updatedAt = updatedAt
                    )
                } else {
                    memory
                }
            }
        }

        override suspend fun deleteById(memoryId: Long) {
            memories.value = memories.value.filterNot { it.id == memoryId }
        }

        override suspend fun deleteAll() {
            memories.value = emptyList()
        }
    }
}
