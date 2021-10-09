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

import java.util.ArrayList;
import java.util.List;

public class Test {
    public static void main(String[] args) {
        String applicationName = "<<CODEDEPLOY APPLICATION NAME>>";
        String deploymentGroupName = "CODEDEPLOY DEPLOYMENT GROUPNAME";
        Regions region = Regions.US_EAST_1;

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

        System.out.println("HTTPS Listener Arn : "+httpsListenerArn);
        System.out.println("Target Group1 : "+targetGroup1);
        System.out.println("Target Group2 : "+targetGroup2);


        AmazonECS amazonECS = AmazonECSClient.builder().withRegion(region)
                .build();

        DescribeServicesRequest describeServicesRequest = new DescribeServicesRequest();
        List<String> services = new ArrayList<>();
        services.add(serviceName);
        describeServicesRequest.setServices(services);
        describeServicesRequest.setCluster(clusterName);
        DescribeServicesResult describeServicesResult = amazonECS.describeServices(describeServicesRequest);
        String currentTargetGroupArn = describeServicesResult.getServices().get(0).getLoadBalancers().get(0).getTargetGroupArn();

        System.out.println("Current TargetGroup Arn :"+currentTargetGroupArn);


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

        System.out.println("Previous TargetGroup Arn :"+previousTargetGroupArn);
        System.out.println("LoadBalancer Arn :"+primaryLoadBalancerArn);

        DescribeLoadBalancersRequest describeLoadBalancersRequest = new DescribeLoadBalancersRequest();
        List<String> loadBalancerArns = new ArrayList<>();
        loadBalancerArns.add(primaryLoadBalancerArn);

        describeLoadBalancersRequest.setLoadBalancerArns(loadBalancerArns);
        DescribeLoadBalancersResult describeLoadBalancersResult = amazonElasticLoadBalancing.describeLoadBalancers(describeLoadBalancersRequest);

        LoadBalancer loadBalancer = describeLoadBalancersResult.getLoadBalancers().get(0);

        DescribeListenersRequest describeListenersRequest = new DescribeListenersRequest();
        describeListenersRequest.setLoadBalancerArn(loadBalancer.getLoadBalancerArn());

        DescribeListenersResult describeListenersResult = amazonElasticLoadBalancing.describeListeners(describeListenersRequest);

        Listener httpListener = describeListenersResult.getListeners().stream().filter((lis) -> lis.getPort() == 443).findFirst().get();
        System.out.println("Http Listener Arn :"+httpListener.getListenerArn());

        DescribeRulesRequest describeRulesRequest = new DescribeRulesRequest();
        describeRulesRequest.setListenerArn(httpListener.getListenerArn());

        DescribeRulesResult describeRulesResult = amazonElasticLoadBalancing.describeRules(describeRulesRequest);


        for(Rule rule : describeRulesResult.getRules()) {
            if(previousTargetGroupArn.equals(rule.getActions().get(0).getTargetGroupArn())) {
                System.out.println("Rule Path :"+rule.getConditions().get(0).getPathPatternConfig().toString());
                System.out.println("Rule TargetGroup :"+rule.getActions().get(0).getTargetGroupArn());

                rule.getActions().get(0).setTargetGroupArn(currentTargetGroupArn);
                rule.getActions().get(0).getForwardConfig().getTargetGroups().get(0).setTargetGroupArn(currentTargetGroupArn);

                ModifyRuleRequest modifyRuleRequest = new ModifyRuleRequest();
                modifyRuleRequest.setRuleArn(rule.getRuleArn());
                modifyRuleRequest.setActions(rule.getActions());

                ModifyRuleResult modifyRuleResult = amazonElasticLoadBalancing.modifyRule(modifyRuleRequest);
                System.out.println("Rule Updated with new Target Group :" +currentTargetGroupArn);
            }
        }


    }
}
