package dev.filmroom.effects
import org.junit.Assert.*
import org.junit.Test
class EffectAdjacentCacheTest {
    @Test fun bothNeighboursRemainUntilTransferred(){val freed=mutableListOf<String>();val c=EffectAdjacentCache<String>{freed+=it};c.put("prev","p");c.put("next","n");assertEquals(2,c.size);assertEquals("p",c.take("prev"));assertTrue(freed.isEmpty());assertEquals("n",c.take("next"));assertEquals(0,c.size)}
    @Test fun thirdEntryEvictsOldestExactlyOnce(){val freed=mutableListOf<Int>();val c=EffectAdjacentCache<Int>{freed+=it};c.put("a",1);c.put("b",2);c.put("c",3);assertEquals(listOf(1),freed);assertFalse(c.contains("a"));assertEquals(2,c.size)}
    @Test fun cancellationClearCannotRecycleTransferredPage(){val freed=mutableListOf<String>();val c=EffectAdjacentCache<String>{freed+=it};c.put("a","a");c.put("b","b");val active=c.take("a");c.clear();c.clear();assertEquals("a",active);assertEquals(listOf("b"),freed)}
    @Test fun movingPageRetainsOnlyNewNeighbours(){val freed=mutableListOf<String>();val c=EffectAdjacentCache<String>{freed+=it};c.put("a","a");c.put("b","b");c.retain(setOf("b","c"));assertEquals(listOf("a"),freed);assertTrue(c.contains("b"));c.put("c","c");assertEquals(2,c.size)}

    private class Photo(val id:String,val revision:Int=0) { var releases=0 }
    @Test fun leavingAndReturningReusesTheExactBitmapOwnerWithoutDecoding(){
        val c=EffectAdjacentCache<Photo>{it.releases++}
        val first=Photo("1");val second=Photo("2");val third=Photo("3")
        var current=first;c.put("2",second)
        val next=c.take("2")!!;c.put(current.id,current);current=next
        c.put("3",third)
        val back=c.take("1")!!;c.retain(setOf("2"));c.put(current.id,current);current=back
        assertSame(first,current);assertSame(second,c.take("2"))
        assertEquals(0,first.releases);assertEquals(0,second.releases);assertEquals(1,third.releases)
    }
    @Test fun rapidBackAndForthKeepsOnlyIdleNeighboursAndOneActiveOwner(){
        val c=EffectAdjacentCache<Photo>{it.releases++};val a=Photo("a");val b=Photo("b")
        var current=a;c.put("b",b)
        repeat(100){val target=if(current===a)"b" else "a";val next=c.take(target)!!;c.put(current.id,current);current=next;assertEquals(1,c.size)}
        assertSame(a,current);assertEquals(0,a.releases);assertEquals(0,b.releases)
        c.clear();assertEquals(0,a.releases);assertEquals(1,b.releases)
    }
    @Test fun cancelledSwipeReturnsOwnershipInsteadOfReleasingPhoto(){
        val c=EffectAdjacentCache<Photo>{it.releases++};val next=Photo("2");c.put("2",next)
        val candidate=c.take("2")!!;c.put("2",candidate)
        assertSame(next,c.take("2"));assertEquals(0,next.releases)
    }
    @Test fun trimAndBackgroundReleaseIdleButNotCurrentOrTransferred(){
        val c=EffectAdjacentCache<Photo>{it.releases++};val current=Photo("1");val next=Photo("2");val previous=Photo("0")
        c.put("0",previous);c.put("2",next);val incoming=c.take("2")!!
        c.clear();c.clear()
        assertEquals(1,previous.releases);assertEquals(0,current.releases);assertEquals(0,incoming.releases)
    }
    @Test fun changedCompositionOrPhotoLinkRejectsSameIdCache(){
        val c=EffectAdjacentCache<Photo>{it.releases++};val stale=Photo("1",0);c.put("1",stale)
        assertNull(c.take("1"){it.revision==1});assertEquals(1,stale.releases);assertEquals(0,c.size)
        val updated=Photo("1",1);c.put("1",updated);assertSame(updated,c.take("1"){it.revision==1})
    }
}
