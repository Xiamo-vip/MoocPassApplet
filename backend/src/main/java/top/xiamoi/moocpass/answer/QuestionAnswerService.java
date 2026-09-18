package top.xiamoi.moocpass.answer;

import org.springframework.stereotype.Service;
import top.xiamoi.moocpass.common.ApiException;
import top.xiamoi.moocpass.infrastructure.*;
import top.xiamoi.moocpass.task.*;
import java.util.*;

@Service
public class QuestionAnswerService {
    private final Db db;
    private final AnswerNormalizer normalizer;
    private final ProfileService profiles;
    private final AnswerClient client;
    public QuestionAnswerService(Db db,AnswerNormalizer normalizer,ProfileService profiles,AnswerClient client) {
        this.db=db;this.normalizer=normalizer;this.profiles=profiles;this.client=client;
    }
    public record Solved(AnswerNormalizer.Match match,String source) {}
    public Solved solve(String platform,String questionId,String type,String title,List<String> options,int blanks) {
        return solve(platform,questionId,type,title,options,blanks,false);
    }
    public Solved solve(String platform,String questionId,String type,String title,List<String> options,int blanks,boolean partialDecode) {
        var scope=ExecutionScope.current();
        ExecutionScope.check();
        List<String> choices=options==null?List.of():options;
        String key=normalizer.questionKey(platform,type,title,choices,scope.resourceId()+":"+questionId+":"+blanks+(partialDecode?":partial-font":""));
        var existing=db.one("SELECT answer,state,source FROM mp_question_result WHERE task_id=? AND question_key=? AND user_id=?",
            scope.taskId(),key,scope.userId());
        if(existing.isPresent() && !Db.text(existing.get(),"answer").isBlank()) {
            var match=normalizer.resolve(type,Db.text(existing.get(),"answer"),choices,blanks);
            if("NEEDS_REVIEW".equals(Db.text(existing.get(),"state")))
                match=new AnswerNormalizer.Match(match.matched(),true,match.value(),match.indices(),match.blanks(),match.method());
            String existingSource=Db.text(existing.get(),"source");
            if(!"RANDOM".equals(existingSource)) {
                if(match.matched()&&!match.needsReview()) return new Solved(match,existingSource);
                if(!scope.settings().aiBestEffort()) return new Solved(match,existingSource);
            }
        }
        String raw="",source="NONE";
        if(Set.of("single","multiple","judgement","completion").contains(type)
            || ("subjective".equals(type)&&(scope.settings().aiBestEffort()||partialDecode))) {
            var cache=partialDecode?Optional.<Map<String,Object>>empty()
                :db.one("SELECT answer,source FROM mp_answer_cache WHERE user_id=? AND question_key=?",scope.userId(),key);
            var cachedMatch=cache.isPresent()&&!"subjective".equals(type)
                ?normalizer.resolve(type,Db.text(cache.get(),"answer"),choices,blanks):AnswerNormalizer.Match.missing();
            if(cachedMatch.matched()&&!cachedMatch.needsReview()) {
                raw=Db.text(cache.get(),"answer");source="CACHE";
            }
            else {
                var settings=scope.settings();
                try {
                    var profile=profiles.resolve(scope.userId(),settings.answerProfileId(),settings.answerVersionId());
                    ProfileService.Resolved fallback=settings.fallbackProfileId()==null?null
                        :profiles.resolve(scope.userId(),settings.fallbackProfileId(),settings.fallbackVersionId());
                    if(partialDecode) {
                        var aiProfile="AI".equals(profile.kind())?profile
                            :fallback!=null&&"AI".equals(fallback.kind())?fallback:null;
                        if(aiProfile!=null) {
                            String prompt="【字体识别提示】题干或选项可能残留加密乱码，[未识别:字]是无法确认的原始字形编码，"
                                +"不是题目中的真实文字，也不是填空位置。请结合已知内容和选项推断题意；不要将乱码当作事实。\n"+title;
                            try {
                                var answer=askWithInterval(aiProfile,type,prompt,choices,blanks,settings.aiBestEffort());
                                raw=answer.text();source="AI_PARTIAL";
                            } catch(ApiException error) {
                                if(!Set.of("PROVIDER_NO_ANSWER","PROVIDER_RESPONSE_INVALID").contains(error.code())) throw error;
                            }
                        }
                    } else {
                        if(!"subjective".equals(type)) {
                            try {
                                var answer=askWithInterval(profile,type,title,choices,blanks,false);
                                raw=answer.text();source=answer.source();
                            } catch(ApiException error) {
                                if(!Set.of("PROVIDER_NO_ANSWER","PROVIDER_RESPONSE_INVALID").contains(error.code())) throw error;
                            }
                            var firstMatch=normalizer.resolve(type,raw,choices,blanks);
                            if((!firstMatch.matched()||firstMatch.needsReview())&&fallback!=null) {
                                try {
                                    var answer=askWithInterval(fallback,type,title,choices,blanks,false);
                                    raw=answer.text();source=answer.source();
                                } catch(ApiException error) {
                                    if(!Set.of("PROVIDER_NO_ANSWER","PROVIDER_RESPONSE_INVALID").contains(error.code())) throw error;
                                }
                            }
                        }
                        var currentMatch=normalizer.resolve(type,raw,choices,blanks);
                        if(settings.aiBestEffort()&&(!currentMatch.matched()||currentMatch.needsReview())) {
                            var aiProfile="AI".equals(profile.kind())?profile
                                :fallback!=null&&"AI".equals(fallback.kind())?fallback:null;
                            if(aiProfile!=null) {
                                var guess=askWithInterval(aiProfile,type,title,choices,blanks,true);
                                raw=guess.text();source="AI_GUESS";
                            }
                        }
                        }
                } catch(ApiException error) {
                    String state=Set.of("QUOTA_EXCEEDED","PROVIDER_RATE_LIMITED").contains(error.code())?"WAITING_QUOTA"
                        :Set.of("SECRET_REVOKED","PROVIDER_AUTH_FAILED","INVALID_REQUEST").contains(error.code())?"WAITING_USER":"RETRY_WAIT";
                    throw new TaskSignal(state,error.code(),error.getMessage());
                }
            }
        }
        var match=normalizer.resolve(type,raw,choices,blanks);
        String state=match.matched()?(match.needsReview()?"NEEDS_REVIEW":"GENERATED"):"NO_ANSWER";
        if("AI_GUESS".equals(source)&&match.matched()&&!match.needsReview()) state="GUESSED";
        db.update("INSERT INTO mp_question_result(user_id,task_id,question_key,question_type,question,options_json,answer,source,state) "
            +"VALUES(?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE answer=VALUES(answer),source=VALUES(source),state=VALUES(state)",
            scope.userId(),scope.taskId(),key,type,title,Json.write(Map.of("choices",choices,"blankCount",blanks)),
            match.matched()?match.value():"",source,state);
        if(!partialDecode&&match.matched()&&!match.needsReview()&&!Set.of("CACHE","AI_GUESS").contains(source)) {
            db.update("INSERT INTO mp_answer_cache(user_id,question_key,answer,source) VALUES(?,?,?,?) "
                +"ON DUPLICATE KEY UPDATE answer=VALUES(answer),source=VALUES(source),updated_at=CURRENT_TIMESTAMP",
                scope.userId(),key,raw,source);
        }
        return new Solved(match,source);
    }
    private AnswerClient.Answer askWithInterval(ProfileService.Resolved profile,String type,String title,List<String> options,int blanks,boolean bestEffort) {
        for(int i=0;i<3;i++) {
            ExecutionScope.check();
            try { return client.ask(profile,ExecutionScope.current().taskId(),type,title,options,blanks,bestEffort); }
            catch(ApiException error) {
                if(!"PROVIDER_INTERVAL".equals(error.code())||i==2) throw error;
                ExecutionScope.sleep(profile.settings().intervalSeconds()*1000L);
            }
        }
        throw new IllegalStateException("Unreachable request state");
    }
    public List<Map<String,Object>> list(long userId,long taskId) {
        if(db.count("SELECT COUNT(*) FROM mp_task WHERE id=? AND user_id=?",taskId,userId)!=1) throw ApiException.missing();
        var rows=db.list("SELECT id,question_type,question,options_json,answer,source,state,created_at "
            +"FROM mp_question_result WHERE user_id=? AND task_id=? ORDER BY id LIMIT 500",userId,taskId);
        rows.forEach(row -> { row.put("options",Json.read(Db.text(row,"optionsJson")));row.remove("optionsJson"); });
        return rows;
    }
}
