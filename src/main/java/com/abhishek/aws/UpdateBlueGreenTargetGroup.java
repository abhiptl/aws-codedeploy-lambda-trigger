package com.abhishek.aws;

import com.amazonaws.regions.Regions;
import com.amazonaws.services.codedeploy.AmazonCodeDeploy;
import com.amazonaws.services.codedeploy.AmazonCodeDeployClient;
import com.amazonaws.services.codedeploy.model.GetDeploymentGroupRequest;
import com.amazonaws.services.codedeploy.model.GetDeploymentGroupResult;
import com.amazonaws.services.ecs.AmazonECS;
import com.amazonaws.services.ecs.AmazonECSClient;
import com.amazonaws.services.ecs.model.DescribeServicesRequest;
import com.amazonaws.services.ecs.model.DescribeServicesResult;
import com.amazonaws.services.elasticloadbalancingv2.AmazonElasticLoadBalancing;
import com.amazonaws.services.elasticloadbalancingv2.AmazonElasticLoadBalancingClient;
import com.amazonaws.services.elasticloadbalancingv2.model.*;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;
import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.List;

public class UpdateBlueGreenTargetGroup implements RequestHandler<SNSEvent, String> {

    @Override
    public String handleRequest(SNSEvent snsEvent, Context context) {
        context.getLogger().log("Lambda Started");
        context.getLogger().log("Records => "+snsEvent.getRecords());

        context.getLogger().log("Message => "+snsEvent.getRecords().get(0).getSNS().getMessage());

        Gson gson = new Gson();
        SNSMessage snsMessage = gson.fromJson(snsEvent.getRecords().get(0).getSNS().getMessage(), SNSMessage.class);

        String applicationName = snsMessage.getApplicationName();
        String deploymentGroupName = snsMessage.getDeploymentGroupName();
        Regions region = Regions.fromName(snsMessage.getRegion());

        context.getLogger().log("ApplicationName => "+applicationName);
        context.getLogger().log("DeploymentGroupName => "+deploymentGroupName);
        context.getLogger().log("Region => "+region.getName());
        context.getLogger().log("Status => "+snsMessage.getStatus());
        context.getLogger().log("DeploymentId => "+snsMessage.getDeploymentId());

        if("SUCCEEDED".equals(snsMessage.getStatus())) {

            AmazonCodeDeploy amazonCodeDeploy = AmazonCodeDeployClient.builder().withRegion(region)
                    .build();

            GetDeploymentGroupRequest getDeploymentGroupRequest = new GetDeploymentGroupRequest();
            getDeploymentGroupRequest.setApplicationName(applicationName);
            getDeploymentGroupRequest.setDeploymentGroupName(deploymentGroupName);

            GetDeploymentGroupResult getDeploymentGroupResult = amazonCodeDeploy.getDeploymentGroup(getDeploymentGroupRequest);
            String serviceName = getDeploymentGroupResult.getDeploymentGroupInfo().getEcsServices().get(0).getServiceName();
            String clusterName = getDeploymentGroupResult.getDeploymentGroupInfo().getEcsServices().get(0).getClusterName();

            String httpsListenerArn = getDeploymentGroupResult.getDeploymentGroupInfo().getLoadBalancerInfo().getTargetGroupPairInfoList().get(0).getProdTrafficRoute().getListenerArns().get(0);
            String targetGroup1 = getDeploymentGroupResult.getDeploymentGroupInfo().getLoadBalancerInfo().getTargetGroupPairInfoList().get(0).getTargetGroups().get(0).getName();
            String targetGroup2 = getDeploymentGroupResult.getDeploymentGroupInfo().getLoadBalancerInfo().getTargetGroupPairInfoList().get(0).getTargetGroups().get(1).getName();

            context.getLogger().log("HTTPS Listener Arn => "+httpsListenerArn);
            context.getLogger().log("Target Group1 => "+targetGroup1);
            context.getLogger().log("Target Group2 => "+targetGroup2);


            AmazonECS amazonECS = AmazonECSClient.builder().withRegion(region)
                    .build();

            DescribeServicesRequest describeServicesRequest = new DescribeServicesRequest();
            List<String> services = new ArrayList<>();
            services.add(serviceName);
            describeServicesRequest.setServices(services);
            describeServicesRequest.setCluster(clusterName);
            DescribeServicesResult describeServicesResult = amazonECS.describeServices(describeServicesRequest);
            String currentTargetGroupArn = describeServicesResult.getServices().get(0).getLoadBalancers().get(0).getTargetGroupArn();

            context.getLogger().log("Current TargetGroup Arn => "+currentTargetGroupArn);


            AmazonElasticLoadBalancing amazonElasticLoadBalancing = AmazonElasticLoadBalancingClient.builder().withRegion(region).build();

            DescribeTargetGroupsRequest describeTargetGroupsRequest = new DescribeTargetGroupsRequest();
            List<String> targetGroupNames = new ArrayList<>();
            targetGroupNames.add(targetGroup1);
            targetGroupNames.add(targetGroup2);
            describeTargetGroupsRequest.setNames(targetGroupNames);

            DescribeTargetGroupsResult describeTargetGroupsResult = amazonElasticLoadBalancing.describeTargetGroups(describeTargetGroupsRequest);
            String targetGroup1Arn = describeTargetGroupsResult.getTargetGroups().get(0).getTargetGroupArn();
            String targetGroup2Arn = describeTargetGroupsResult.getTargetGroups().get(1).getTargetGroupArn();

            String primaryLoadBalancerArn = null;
            String previousTargetGroupArn = targetGroup1Arn;
            if(currentTargetGroupArn.equalsIgnoreCase(targetGroup1Arn)) {
                previousTargetGroupArn = targetGroup2Arn;
                primaryLoadBalancerArn = describeTargetGroupsResult.getTargetGroups().get(1).getLoadBalancerArns().get(0);
            } else {
                previousTargetGroupArn = targetGroup1Arn;
                primaryLoadBalancerArn = describeTargetGroupsResult.getTargetGroups().get(0).getLoadBalancerArns().get(0);
            }

            context.getLogger().log("Previous TargetGroup Arn => "+previousTargetGroupArn);
            context.getLogger().log("LoadBalancer Arn => "+primaryLoadBalancerArn);

            DescribeLoadBalancersRequest describeLoadBalancersRequest = new DescribeLoadBalancersRequest();
            List<String> loadBalancerArns = new ArrayList<>();
            loadBalancerArns.add(primaryLoadBalancerArn);

            describeLoadBalancersRequest.setLoadBalancerArns(loadBalancerArns);
            DescribeLoadBalancersResult describeLoadBalancersResult = amazonElasticLoadBalancing.describeLoadBalancers(describeLoadBalancersRequest);

            LoadBalancer loadBalancer = describeLoadBalancersResult.getLoadBalancers().get(0);

            DescribeListenersRequest describeListenersRequest = new DescribeListenersRequest();
            describeListenersRequest.setLoadBalancerArn(loadBalancer.getLoadBalancerArn());

            DescribeListenersResult describeListenersResult = amazonElasticLoadBalancing.describeListeners(describeListenersRequest);

            Listener httpListener = describeListenersResult.getListeners().stream().filter((lis) -> lis.getPort() == 80).findFirst().get();
            context.getLogger().log("Http Listener Arn => "+httpListener.getListenerArn());

            DescribeRulesRequest describeRulesRequest = new DescribeRulesRequest();
            describeRulesRequest.setListenerArn(httpListener.getListenerArn());

            DescribeRulesResult describeRulesResult = amazonElasticLoadBalancing.describeRules(describeRulesRequest);


            for(Rule rule : describeRulesResult.getRules()) {
                if(previousTargetGroupArn.equals(rule.getActions().get(0).getTargetGroupArn())) {
                    context.getLogger().log("Rule Path => "+rule.getConditions().get(0).getPathPatternConfig().toString());
                    context.getLogger().log("Rule TargetGroup => "+rule.getActions().get(0).getTargetGroupArn());

                    rule.getActions().get(0).setTargetGroupArn(currentTargetGroupArn);
                    rule.getActions().get(0).getForwardConfig().getTargetGroups().get(0).setTargetGroupArn(currentTargetGroupArn);

                    ModifyRuleRequest modifyRuleRequest = new ModifyRuleRequest();
                    modifyRuleRequest.setRuleArn(rule.getRuleArn());
                    modifyRuleRequest.setActions(rule.getActions());

                    ModifyRuleResult modifyRuleResult = amazonElasticLoadBalancing.modifyRule(modifyRuleRequest);
                    context.getLogger().log("Rule Updated with new Target Group => " +currentTargetGroupArn);
                }
            }

        } else {
            context.getLogger().log("CodeDeployment Status is not Success : "+snsMessage.getStatus());
        }

        context.getLogger().log("Lambda Ended");
        return "SUCCESS";
    }
}
