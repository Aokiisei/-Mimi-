package com.wuyi.kebiao;

/**
 * 本文件职责：
 * 成绩页 Fragment。
 * 直接继承 HomeFragment，仅把 isGradeMode() 置为 true 以切换到成绩模式。
 */

public class GradeFragment extends HomeFragment {
    @Override
    public boolean isGradeMode() {
        return true;
    }
}
