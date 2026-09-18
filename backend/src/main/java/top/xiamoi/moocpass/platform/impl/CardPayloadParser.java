package top.xiamoi.moocpass.platform.impl;

import tools.jackson.databind.JsonNode;
import top.xiamoi.moocpass.infrastructure.Json;
import java.util.regex.Pattern;

public final class CardPayloadParser {
    private static final Pattern ASSIGNMENT=Pattern.compile("\\bmArg\\s*=\\s*\\{");
    private CardPayloadParser() {}
    public static JsonNode parse(String html) {
        var matcher=ASSIGNMENT.matcher(html);
        if(!matcher.find()) return null;
        int start=matcher.end()-1,depth=0;
        boolean string=false,escape=false;
        for(int i=start;i<html.length();i++) {
            char c=html.charAt(i);
            if(string) {
                if(escape) escape=false;
                else if(c=='\\') escape=true;
                else if(c=='"') string=false;
            } else if(c=='"') string=true;
            else if(c=='{') depth++;
            else if(c=='}'&&--depth==0) return Json.read(html.substring(start,i+1));
        }
        throw new IllegalArgumentException("Course card payload is incomplete");
    }
}
