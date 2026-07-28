import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TestAgent {

    interface GreetingExpert {

        @UserMessage("以下文本是什么任务： {{it}}")
        TASKTYPE isTask(String text);

    }

    interface ChatBot {

        @SystemMessage("你是一名航空公司客服代理，请为客户服务：")
        String reply(String userMessage);
    }

    class MilesOfSmiles {

        private GreetingExpert greetingExpert;
        private ChatBot chatBot;

        public MilesOfSmiles(GreetingExpert greetingExpert, ChatBot chatBot) {
            this.greetingExpert = greetingExpert;
            this.chatBot = chatBot;
        }

        public String handle(String userMessage) {
            TASKTYPE task = greetingExpert.isTask(userMessage);

            switch (task) {
                case MODIFY_TICKET:
                case QUERY_TICKET:
                case CANCEL_TICKET:
                    return task.getName() + "调用service方法处理";
                case OTHER:
                    return chatBot.reply(userMessage);
            }
            return null;
        }

    }

    ChatLanguageModel qwen;

    ChatLanguageModel deepseek;

    @BeforeEach
    public void init() {
        qwen = QwenChatModel
                .builder()
                .apiKey(System.getenv("ALI_AI_KEY"))
                .modelName("qwen-max")
                .build();

        deepseek = OpenAiChatModel
                .builder()
                .baseUrl("https://api.deepseek.com")
                .apiKey(System.getenv("DEEP_SEEK_KEY"))
                .modelName("deepseek-reasoner")
                .build();
    }


    @Test
    void test() {
        GreetingExpert greetingExpert = AiServices.create(GreetingExpert.class, deepseek);

        ChatBot chatBot = AiServices.create(ChatBot.class, qwen);

        MilesOfSmiles milesOfSmiles = new MilesOfSmiles(greetingExpert, chatBot);

        String greeting = milesOfSmiles.handle("我要退票！");
        System.out.println(greeting);


    }
}
