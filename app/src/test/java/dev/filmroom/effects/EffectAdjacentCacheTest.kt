package dev.filmroom.effects
import org.junit.Assert.*
import org.junit.Test
class EffectAdjacentCacheTest {
    @Test fun bothNeighboursRemainUntilTransferred(){val freed=mutableListOf<String>();val c=EffectAdjacentCache<String>{freed+=it};c.put("prev","p");c.put("next","n");assertEquals(2,c.size);assertEquals("p",c.take("prev"));assertTrue(freed.isEmpty());assertEquals("n",c.take("next"));assertEquals(0,c.size)}
    @Test fun thirdEntryEvictsOldestExactlyOnce(){val freed=mutableListOf<Int>();val c=EffectAdjacentCache<Int>{freed+=it};c.put("a",1);c.put("b",2);c.put("c",3);assertEquals(listOf(1),freed);assertFalse(c.contains("a"));assertEquals(2,c.size)}
    @Test fun cancellationClearCannotRecycleTransferredPage(){val freed=mutableListOf<String>();val c=EffectAdjacentCache<String>{freed+=it};c.put("a","a");c.put("b","b");val active=c.take("a");c.clear();c.clear();assertEquals("a",active);assertEquals(listOf("b"),freed)}
    @Test fun movingPageRetainsOnlyNewNeighbours(){val freed=mutableListOf<String>();val c=EffectAdjacentCache<String>{freed+=it};c.put("a","a");c.put("b","b");c.retain(setOf("b","c"));assertEquals(listOf("a"),freed);assertTrue(c.contains("b"));c.put("c","c");assertEquals(2,c.size)}
}
