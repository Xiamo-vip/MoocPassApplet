package top.xiamoi.moocpass.solver;

import top.xiamoi.moocpass.entity.UserQuestionConfig;

import java.util.List;

public interface QuestionSolver {

    String getProviderCode();

    String solve(String question, List<String> options, UserQuestionConfig config);
}
