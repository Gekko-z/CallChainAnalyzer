package io.github.gekkoz.callchain.cli;


import io.github.gekkoz.CallChainAnalyzer;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 调用链分析器的主入口点
 */
public class Main {
    public static void main(String[] args) {
        if (args.length < 3) { // 至少需要3个参数
            System.out.println("用法: java Main <项目路径> <查询类型：0-Mapper(类名，如AppInfoMapper),1-方法(如AppInfoMapper#getDeviceList,AppInfoMapper是类名，不能是驼峰，类与方法名之间使用#连接),2-常量> <查询关键字> [--debug]");
            System.exit(1);
        }

        String projectPath = args[0];
        String searchType = args[1];
        String searchKeywork = args[2];
        boolean debug = args.length > 3 && "--debug".equals(args[3]);

        Set<String> urlList = new HashSet<>();

        System.out.println("正在分析调用链: " + searchKeywork);
        System.out.println("项目路径: " + projectPath);
        System.out.println("查询类型: " + getSearchTypeName(searchType));

        try {
            long startTime = System.currentTimeMillis();
            CallChainAnalyzer analyzer = new CallChainAnalyzer(projectPath, searchType, searchKeywork, debug);
            Map<String, List<List<String>>> allCallChains = analyzer.findAllCallChainsToRestController(searchKeywork);
            long endTime = System.currentTimeMillis();

            System.out.println("分析总耗时: " + (endTime - startTime) + "ms");

            if (allCallChains.isEmpty()) {
                System.out.println("未找到目标到REST控制器的调用链");
            } else {
                System.out.println("找到以下调用链:");
                int chainIndex = 1;
                int totalChainCount = 0;
                // 修改循环逻辑以处理多条调用链
                for (Map.Entry<String, List<List<String>>> entry : allCallChains.entrySet()) {
                    String startPoint = entry.getKey();
                    List<List<String>> callChains = entry.getValue();
                    totalChainCount += callChains.size();

                    for (List<String> callChain : callChains) {
                        System.out.println("\n调用链 #" + chainIndex++);
                        System.out.println("起始方法: " + startPoint);
                        for (int i = callChain.size() - 1; i >= 0; i--) {
                            System.out.println("  " + (callChain.size() - i) + ". " + callChain.get(i));
                        }

                        // 获取并打印Controller方法的URL
                        String controllerMethod = callChain.get(0); // Controller方法在调用链的顶部
                        String url = analyzer.getControllerMethodUrl(controllerMethod);
                        if (!url.isEmpty()) {
                            System.out.println("  URL: " + url);
                            urlList.add(url);
                        } else {
                            System.out.println("  URL: 未找到");
                        }
                    }
                }

                System.out.println("\n总计找到 " + totalChainCount + " 条调用链");
                if (!urlList.isEmpty()) {
                    System.out.println("共计涉及" + urlList.size() +"个接口，去重后的URL列表：" + urlList);
                }
            }
        } catch (Exception e) {
            System.err.println("分析调用链时出错: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static String getSearchTypeName(String searchType) {
        switch (searchType) {
            case "0": return "Mapper类";
            case "1": return "方法调用";
            case "2": return "常量";
            default: return "未知";
        }
    }
}
