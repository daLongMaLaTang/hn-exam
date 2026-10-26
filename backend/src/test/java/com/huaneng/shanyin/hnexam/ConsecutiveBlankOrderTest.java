package com.huaneng.shanyin.hnexam;

import com.huaneng.shanyin.hnexam.service.ConsecutiveBlankOrder;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ConsecutiveBlankOrderTest {
    @Test void detectsOnlyUnambiguousConsecutiveMarkers() {
        assertThat(ConsecutiveBlankOrder.blocks("包括（ ）、（_）和___；温度为＿ ＿", 3)).hasSize(1);
        assertThat(ConsecutiveBlankOrder.blocks("温度___，压力___", 2)).isEmpty();
        assertThat(ConsecutiveBlankOrder.blocks("___。___", 2)).isEmpty();
        assertThat(ConsecutiveBlankOrder.blocks("___、___", 3)).isEmpty();
        assertThat(ConsecutiveBlankOrder.blocks("___、___，另一组___、___", 4))
                .containsExactly(new ConsecutiveBlankOrder.Block(0,2),new ConsecutiveBlankOrder.Block(2,4));
    }
    @Test void matchingDoesNotMoveAnswersBetweenBlocksOrDropEmptyPositions() {
        var expected=List.of("甲","乙","丙","丁");
        assertThat(ConsecutiveBlankOrder.align("___、___，另一组___、___",4,List.of("丁","丙","乙","甲"),
                (slot,text)->expected.get(slot).equals(text))).containsExactly("丁","丙","乙","甲");
        assertThat(ConsecutiveBlankOrder.align("___、___，另一组___、___",4,List.of("乙","","丁","丙"),
                (slot,text)->expected.get(slot).equals(text))).containsExactly("","乙","丙","丁");
    }
}
