package dev.filmroom
import dev.filmroom.model.*
import org.junit.Assert.*
import org.junit.Test
class EditHistoryTest {
 @Test fun undoRedoAndBranch() { val h=EditHistory(); val a=h.current.copy(brightness=1f); h.update(a); assertTrue(h.canUndo); h.undo(); assertEquals(0f,h.current.brightness); assertTrue(h.canRedo); h.redo(); assertEquals(a,h.current); h.undo();h.update(h.current.copy(grain=0f));assertFalse(h.canRedo) }
 @Test fun unchangedDoesNotAddAction() { val h=EditHistory();h.update(h.current);assertFalse(h.canUndo) }
 @Test fun boundedHistory() { val h=EditHistory(); repeat(100) { h.update(h.current.copy(brightness=it.toFloat())) }; repeat(50){h.undo()};assertFalse(h.canUndo) }
}
