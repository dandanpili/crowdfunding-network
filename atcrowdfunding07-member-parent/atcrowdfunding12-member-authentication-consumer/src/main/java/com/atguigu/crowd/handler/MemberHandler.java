package com.atguigu.crowd.handler;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.atguigu.crowd.api.MySQLRemoteService;
import com.atguigu.crowd.api.RedisRemoteService;
import com.atguigu.crowd.config.ShortMessageProperties;
import com.atguigu.crowd.constant.CrowdConstant;
import com.atguigu.crowd.entity.po.MemberPO;
import com.atguigu.crowd.entity.vo.MemberVO;
import com.atguigu.crowd.util.CrowdUtil;
import com.atguigu.crowd.util.ResultEntity;

@Controller
public class MemberHandler {

	@Autowired
	private ShortMessageProperties shortMessageProperties;
	
	@Autowired
	private RedisRemoteService redisRemoteService;
	
	@Autowired
	private MySQLRemoteService mySQLRemoteService;
	
	@RequestMapping("/auth/do/member/register")
	public String register(MemberVO memberVO, ModelMap modelMap) {
		 
		//1.获取用户输入的手机号
		String phoneNum = memberVO.getPhoneNum();
		//2.拼Redis中存储验证码的Key
		String key = CrowdConstant.REDIS_CODE_PREFIX + phoneNum;
		//3.从Redis读取Key对应的Value
		ResultEntity<String> resultEntity = redisRemoteService.getRedisStringValueByKeyRemote(key);
		//4.检查查询操作是否有效
		String result = resultEntity.getResult();
		
		if (ResultEntity.FAILED.equals(result)) {
			modelMap.addAttribute(CrowdConstant.ATTR_NAME_MESSAGE, resultEntity.getMessage());
			return "memeber-reg";
		}
		
		String redisCode = resultEntity.getData();
		if (redisCode == null) {
			modelMap.addAttribute(CrowdConstant.ATTR_NAME_MESSAGE, CrowdConstant.MESSAGE_CODE_NOT_EXISTS);
			return "member-reg";
		}
		//5.如果从Redis能够查询到value则比较表单验证码和Redis验证码
		String formCode = memberVO.getCode();
		if (!Objects.equals(redisCode, formCode)) {
			modelMap.addAttribute(CrowdConstant.ATTR_NAME_MESSAGE, CrowdConstant.MESSAGE_CODE_INVALID);
			return "member-reg";
		}
		//6.如果验证码一直，则从Redis删除
		redisRemoteService.removeRedisKeyRemote(key);
		//7.执行密码加密
		BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
		String userpswdBeforeEncode = memberVO.getUserpswd();
		
		String userpswdAfterEncode = passwordEncoder.encode(userpswdBeforeEncode);
		memberVO.setUserpswd(userpswdAfterEncode);
		
		//8.执行保存
		//memberVO是表单的数据，memberPO是数据库的表数据，需要进行转换
		MemberPO memberPO = new MemberPO();
		BeanUtils.copyProperties(memberVO, memberPO);
		ResultEntity<String> saveMemberResultEntity = mySQLRemoteService.saveMember(memberPO);
		
		if (ResultEntity.FAILED.equals(saveMemberResultEntity.getResult())) {
			modelMap.addAttribute(CrowdConstant.ATTR_NAME_MESSAGE, saveMemberResultEntity.getMessage());
			return "member-reg";
		}
		
		//return "member-login";
		return "portal";
	}
	
	@ResponseBody
	@RequestMapping("/auth/member/send/short/message.json")
	public ResultEntity<String> sendMessage(@RequestParam("phoneNum") String phoneNum){
		
		//1.发送验证码到phoneNum手机
		ResultEntity<String> sendMessageResultEntity = CrowdUtil.sendCodeByShortMessageTwo(
				phoneNum, 
				shortMessageProperties.getAppCode(), 
				shortMessageProperties.getSign(), 
				shortMessageProperties.getSkin(), 
				shortMessageProperties.getHost(), 
				shortMessageProperties.getPath());
		
		//2.判断短信发送结果
		if(ResultEntity.SUCCESS.equals(sendMessageResultEntity.getResult())) {
			//3.如果发送成功，将验证码存入Redis
			//①从sendMessageResultEntity中获取随机生成的验证码
			String code = sendMessageResultEntity.getData();
			//②拼接一个用于在Redis中存储数据的key
			String key = CrowdConstant.REDIS_CODE_PREFIX + phoneNum;
			//③调用远程接口存入Redis
			ResultEntity<String> saveCodeResultEntity = redisRemoteService.setRedisKeyValueRemoteTimeout(key, code, 15, TimeUnit.MINUTES);
			//④判断结果
			if(ResultEntity.SUCCESS.equals(saveCodeResultEntity.getResult())) {
				return ResultEntity.successWithoutData();
			}else {
				return saveCodeResultEntity;
			}
		}else {
			return sendMessageResultEntity;
		}
		
	}
}
