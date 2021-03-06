package com.springboot.lottery.controller;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.ehcache.EhCacheCacheManager;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.springboot.lottery.dto.FundRecordDTO;
import com.springboot.lottery.dto.SingleNoteDTO;
import com.springboot.lottery.entity.Member;
import com.springboot.lottery.entity.MemberFundRecord;
import com.springboot.lottery.entity.MemberSingleNote;
import com.springboot.lottery.service.MemberService;
import com.springboot.lottery.service.impl.MemberServiceImpl;
import com.springboot.lottery.util.BeanLoad;
import com.springboot.lottery.util.MessageUtil;
import com.springboot.lottery.util.ObjectResult;
import com.springboot.lottery.util.Page;
import com.springboot.lottery.util.JsoupUtil;

/**
 * 控制层
 * 
 * @author Administrator
 *
 */
@Controller
@RequestMapping("member")
public class MemberController {

	@Autowired
	private MemberService memberService;// service
	@Autowired
	private EhCacheCacheManager manager;// ehcache缓存
	@Autowired
	private HttpServletRequest request;// 请求

	/**
	 * 20180428会员开户
	 * 
	 * @param member
	 * @return
	 */
	@RequestMapping(value = "add-member", method = RequestMethod.POST)
	@ResponseBody
	public ObjectResult addMemberAll(Member member) {
		ObjectResult result = new ObjectResult();
		// 获取IP地址
		String ipAddress = getIpAddress();
		if (ipAddress.equals(MessageUtil.IP_ADDRESS)) {
			// IP地址获取失败
			result.setCode(MessageUtil.IP_ADDRESS);
			return result;
		}
		Cache cache = getCache();
		String name = member.getName();
		String verifyMember = memberService.verifyMember(name);
		if (verifyMember != null) {
			// 用户名已存在
			result.setCode(MessageUtil.NAME_EXIST);
			return result;
		}
		String mid = BeanLoad.getId();
		String role = member.getRole();
		member.setAddress(ipAddress);// 设置IP地址
		member.setRegister_time(new Date());// 注册时间
		member.setMid(mid);// 设置主键id
		member.setSum("0");// 余额默认设置为0
		member.setRebate("0");// 返利默认设置为0
		member.setRole(role = StringUtils.isBlank(role) ? "0" : role);// 设置权限
		int addMember = memberService.addMember(member);
		if (addMember <= 0) {
			// 修改失败
			result.setCode(MessageUtil.UPDATE_ERROR);
			return result;
		}
		// 产生token
		String token = BeanLoad.getUUID();
		member.setToken(token);
		member.setAddress(ipAddress);
		// 把token做为key放入ehcache缓存中
		cache.put(token, member);
		// 把mid作为key放入ehcache缓存中
		cache.put(member.getMid(), token);
		// 把IP地址作为key放入ehcache缓存中
		cache.put(ipAddress, token);
		result.setResult(toMapByMember(member, token));
		return result;
	}

	/**
	 * 20180428用户名验证
	 * 
	 * @param name
	 *            用户名
	 * @return
	 */
	@RequestMapping(value = "verify-name", method = RequestMethod.GET)
	@ResponseBody
	public ObjectResult verifyName(String name) {
		ObjectResult result = new ObjectResult();
		String verifyMember = memberService.verifyMember(name);
		if (verifyMember == null) {
			return result;
		}
		// 用户名已存在
		result.setCode(MessageUtil.NAME_EXIST);
		return result;
	}
	
	/**
	 * 20180428查询会员信息
	 * 
	 * @param pageNo
	 *            当前页
	 * @param pageSize
	 *            每页显示条数
	 * @param keyword
	 *            关键字
	 * @return
	 */
	@RequestMapping(value = "query-member", method = RequestMethod.POST)
	@ResponseBody
	public Page<Map<String, Object>> queryMember(Integer pageNo, Integer pageSize, String keyword) {
		String token = request.getHeader("token");
		Page<Map<String, Object>> page = new Page<Map<String, Object>>();
		// 获取指定缓存对象
		Cache cache = getCache();
		// token验证
		String tokenVerify = tokenVerify(token, cache);
		if (!tokenVerify.equals(MessageUtil.SUCCESS)) {
			page.setCode(tokenVerify);
			return page;
		}
		// 获取缓存中的数据
		Member tokenMember = (Member) cache.get(token).get();
		if(tokenMember.getRole().equals("1")) {
			Map<String, Object> memberMap = new HashMap<String, Object>();
			memberMap.put("role", "0");
			memberMap.put("keyword", keyword);// 关键字
			memberMap.put("pageSize", pageSize);// 每页显示多少条
			memberMap.put("beginIndex", pageNo == null || pageSize == null ? null : (pageNo - 1) * pageSize);// 下标
			memberMap.put("pageNo", pageNo);// 当前页
			int total = memberService.queryMemberTotal(memberMap);
			List<Member> list = memberService.queryMember(memberMap);
			List<Map<String, Object>> mapByMembers = toMapByMembers(list, null);
			Page<Map<String, Object>> map = new Page<Map<String, Object>>(pageNo, pageSize, total, mapByMembers);
			return map;
		}
		return page;
	}

	/**
	 * 20180604修改密码
	 * 
	 * @param oldPassword
	 *            原密码
	 * @param newPassword
	 *            新密码
	 * @return
	 */
	@RequestMapping(value = "update-password", method = RequestMethod.POST)
	@ResponseBody
	public ObjectResult updatePassword(String state, String oldPassword, String newPassword) {
		ObjectResult result = new ObjectResult();
		String token = request.getHeader("token");
		// 获取指定缓存对象
		Cache cache = getCache();
		// token验证
		String tokenVerify = tokenVerify(token, cache);
		if (!tokenVerify.equals(MessageUtil.SUCCESS)) {
			result.setCode(tokenVerify);
			return result;
		}
		// 获取缓存中的数据
		Member tokenMember = (Member) cache.get(token).get();
		Map<String, Object> map = new HashMap<String, Object>();
		String role = tokenMember.getRole();
		// 重置密码
		if (role.equals("1")) {
			Member member = new Member();
			String cacheToken = null;
			// 根据mid获取token
			if (cache.get(oldPassword) != null) {
				cacheToken = (String) cache.get(oldPassword).get();
				// 获取token中会员信息
				member = (Member) cache.get(cacheToken).get();
			}
			// 0表示取款密码，1表示登录密码
			if (state.equals("0")) {
				String bankPassword = "011c945f30ce2cbafc452f39840f025693339c42";// 取款密码1111
				map.put("bankPassword", newPassword = StringUtils.isBlank(newPassword) ? bankPassword : newPassword);// 设置新密码
				member.setBank_password(newPassword);
			} else if (state.equals("1")) {
				String password = "f7a9e24777ec23212c54d7a350bc5bea5477fdbb";// 登录密码aaaaaa
				map.put("password", newPassword = StringUtils.isBlank(newPassword) ? password : newPassword);// 设置新密码
				member.setPassword(newPassword);
			}
			map.put("mid", oldPassword);// 设置mid
			memberService.updateMember(map);
			// 如果会员token不为空，则更新token中的密码
			if (cacheToken != null) {
				cache.put(cacheToken, member);
			}
			return result;
		}
		// 0表示取款密码，1表示登录密码
		if (state.equals("0")) {
			// 匹配密码是否正确
			if (!tokenMember.getBank_password().equals(oldPassword)) {
				// 密码错误
				result.setCode(MessageUtil.PASSWORD_ERROR); 
				return result;
			}
			if (tokenMember.getBank_password().equals(newPassword)) {
				// 密码相同
				result.setCode(MessageUtil.PASSWORD_ALIKE);
				return result;
			}
			map.put("bankPassword", newPassword);// 设置新密码
			tokenMember.setBank_password(newPassword);
		} else if (state.equals("1")) {
			// 匹配密码是否正确
			if (!tokenMember.getPassword().equals(oldPassword)) {
				// 密码错误
				result.setCode(MessageUtil.PASSWORD_ERROR);
				return result;
			}
			if (tokenMember.getPassword().equals(newPassword)) {
				// 密码相同
				result.setCode(MessageUtil.PASSWORD_ALIKE);
				return result;
			}
			map.put("password", newPassword);// 设置新密码
			tokenMember.setPassword(newPassword);
		} else {
			result.setCode(MessageUtil.PARAMETER_ERROR);
			return result;
		}
		map.put("mid", tokenMember.getMid());// 设置会员id
		int updateMember = memberService.updateMember(map);
		if (updateMember <= 0) {
			result.setCode(MessageUtil.UPDATE_ERROR);
			return result;
		}
		cache.put(token, tokenMember);
		return result;
	}

	/**
	 * 20180428会员登录
	 * 
	 * @param member
	 * @return
	 */
	@RequestMapping(value = "login-member", method = RequestMethod.POST)
	@ResponseBody
	public ObjectResult loginMember(Member member) {
		Cache cache = getCache();
		// 获取用户名
		String name = member.getName();
		// 获取登录密码
		String password = member.getPassword();
		ObjectResult result = new ObjectResult();
		Map<String, Object> map = new HashMap<String, Object>();
		map.put("name", name);
		List<Member> list = memberService.queryMember(map);
		// 判断list对象是否为空
		if (list == null || list.size() != 1) {
			// 会员不存在
			result.setCode(MessageUtil.MEMBER_NOT);
			return result;
		}
		// 获取数据匹配密码
		member = list.get(0);
		String role = member.getRole();
		if (!role.equals("0")) {
			// 会员不存在
			result.setCode(MessageUtil.MEMBER_NOT);
			return result;
		}
		if (!member.getPassword().equals(password)) {
			// 密码错误
			result.setCode(MessageUtil.PASSWORD_ERROR);
			return result;
		}
		// 产生token
		String token = BeanLoad.getUUID();
		// 获取IP地址
		String ipAddress = getIpAddress();
		if (ipAddress.equals(MessageUtil.IP_ADDRESS)) {
			// IP地址获取失败
			result.setCode(MessageUtil.IP_ADDRESS);
			return result;
		}
		if (cache.get(ipAddress) != null && cache.get(member.getMid()) != null) {
			// 获取token
			String cacheToken = (String) cache.get(member.getMid()).get();
			if (cache.get(cacheToken) != null) {
				// 获取token中会员信息
				Member tokenMember = (Member) cache.get(cacheToken).get();
				// 设置新token比较旧token
				tokenMember.setToken(token);
				// 移除旧token
				cache.evict(cacheToken);
				// 再添加旧token
				cache.put(cacheToken, tokenMember);
			}
		}
		if (cache.get(member.getMid()) != null) {
			// 获取token
			String cacheToken = (String) cache.get(member.getMid()).get();
			if (cache.get(cacheToken) != null) {
				// 获取token中会员信息
				Member tokenMember = (Member) cache.get(cacheToken).get();
				if (member.getMid().equals(tokenMember.getMid())) {
					// 移除mid缓存
					cache.evict(tokenMember.getMid());
					// 移除IP地址缓存
					cache.evict(tokenMember.getAddress());
				}
			}
		}
		if (cache.get(ipAddress) != null) {
			// 获取token
			String cacheToken = (String) cache.get(ipAddress).get();
			if (cache.get(cacheToken) != null) {
				// 获取token中会员信息
				Member tokenMember = (Member) cache.get(cacheToken).get();
				// 同一地址允许管理员和会员同时登录
				if (tokenMember.getRole().equals(member.getRole())) {
					if (ipAddress.equals(tokenMember.getAddress())) {
						// 移除mid缓存
						cache.evict(tokenMember.getMid());
						// 移除IP地址缓存
						cache.evict(tokenMember.getAddress());
					}
				}
			}
		}
		// 设置IP地址
		member.setAddress(ipAddress);
		// 设置token
		member.setToken(token);
		// 把token做为key放入ehcache缓存中
		cache.put(token, member);
		// 把mid作为key放入ehcache缓存中
		cache.put(member.getMid(), token);
		// 把IP地址作为key放入ehcache缓存中
		cache.put(ipAddress, token);
		result.setResult(toMapByMember(member, token));
		return result;
	}

	/**
	 * 20180522管理员登录
	 * 
	 * @param member
	 * @return
	 */
	@RequestMapping(value = "login-admin", method = RequestMethod.POST)
	@ResponseBody
	public ObjectResult loginAdmin(Member member) {
		Cache cache = getCache();
		// 获取用户名
		String name = member.getName();
		// 获取登录密码
		String password = member.getPassword();
		ObjectResult result = new ObjectResult();
		Map<String, Object> map = new HashMap<String, Object>();
		map.put("name", name);
		List<Member> list = memberService.queryMember(map);
		// 判断list对象是否为空
		if (list == null || list.size() != 1) {
			// 会员不存在
			result.setCode(MessageUtil.MEMBER_NOT);
			return result;
		}
		// 获取数据匹配密码
		member = list.get(0);
		String role = member.getRole();
		if (!role.equals("1")) {
			// 会员不存在
			result.setCode(MessageUtil.MEMBER_NOT);
			return result;
		}
		if (!member.getPassword().equals(password)) {
			// 密码错误
			result.setCode(MessageUtil.PASSWORD_ERROR);
			return result;
		}
		// 产生token
		String token = BeanLoad.getUUID();
		// 获取IP地址
		String ipAddress = getIpAddress();
		if (ipAddress.equals(MessageUtil.IP_ADDRESS)) {
			// IP地址获取失败
			result.setCode(MessageUtil.IP_ADDRESS);
			return result;
		}
		if (cache.get(ipAddress) != null && cache.get(member.getMid()) != null) {
			// 获取token
			String cacheToken = (String) cache.get(member.getMid()).get();
			if (cache.get(cacheToken) != null) {
				// 获取token中会员信息
				Member tokenMember = (Member) cache.get(cacheToken).get();
				// 设置新token比较旧token
				tokenMember.setToken(token);
				// 移除旧token
				cache.evict(cacheToken);
				// 再添加旧token
				cache.put(cacheToken, tokenMember);
			}
		}
		if (cache.get(member.getMid()) != null) {
			// 获取token
			String cacheToken = (String) cache.get(member.getMid()).get();
			if (cache.get(cacheToken) != null) {
				// 获取token中会员信息
				Member tokenMember = (Member) cache.get(cacheToken).get();
				if (member.getMid().equals(tokenMember.getMid())) {
					// 移除mid缓存
					cache.evict(tokenMember.getMid());
					// 移除IP地址缓存
					cache.evict(tokenMember.getAddress());
				}
			}
		}
		// 设置IP地址
		member.setAddress(ipAddress);
		// 设置token
		member.setToken(token);
		// 把token做为key放入ehcache缓存中
		cache.put(token, member);
		// 把mid作为key放入ehcache缓存中
		cache.put(member.getMid(), token);
		// 把IP地址作为key放入ehcache缓存中
		cache.put(ipAddress, token);
		result.setResult(toMapByMember(member, token));
		return result;
	}

	/**
	 * 20180428会员登出
	 * 
	 * @return
	 */
	@RequestMapping(value = "exit-member", method = RequestMethod.POST)
	@ResponseBody
	public ObjectResult memberExit() {
		String token = request.getHeader("token");
		ObjectResult result = new ObjectResult();
		Cache cache = getCache();
		// 判断token是否为空
		if (StringUtils.isBlank(token)) {
			return result;
		}
		// 判断token是否过期
		if (cache.get(token) == null) {
			return result;
		}
		// 获取缓存中的数据
		Member member = (Member) cache.get(token).get();
		// 移除mid缓存
		cache.evict(member.getMid());
		// 移除IP缓存
		cache.evict(member.getAddress());
		// 移除token
		cache.evict(token);
		return result;
	}

	/**
	 * 20180507会员余额
	 * 
	 * @return
	 */
	@RequestMapping(value = "member-money", method = RequestMethod.GET)
	@ResponseBody
	public ObjectResult getMemberByMoney() {
		String token = request.getHeader("token");
		ObjectResult result = new ObjectResult();
		// 获取指定缓存对象
		Cache cache = getCache();
		// token验证
		String tokenVerify = tokenVerify(token, cache);
		if (!tokenVerify.equals(MessageUtil.SUCCESS)) {
			result.setCode(tokenVerify);
			return result;
		}
		// 获取缓存中的数据
		Member tokenMember = (Member) cache.get(token).get();
		// 获取缓存中的会员id
		String mid = tokenMember.getMid();
		// 创建一个map对象
		Map<String, Object> map = new HashMap<String, Object>();
		// 根据id查询账户余额
		map.put("mid", mid);
		List<Member> memberList = memberService.queryMember(map);
		if (memberList == null || memberList.size() > 1) {
			System.err.println("mid查询不到数据");
			// 数据匹配错误
			result.setCode(MessageUtil.DATA_NOT_FOUND);
			return result;
		}
		Member member = memberList.get(0);
		map.remove("mid");
		// 往map中添加键值对返回
		map.put("name", member.getName());
		map.put("sum", member.getSum());
		map.put("rebate", member.getRebate());
		result.setResult(map);
		return result;
	}

	/**
	 * 20180516汇率转换
	 * 
	 * @param currency
	 *            货币
	 * @param record
	 *            存取款
	 * @return
	 */
	@RequestMapping(value = "money-exchange", method = RequestMethod.POST)
	@ResponseBody
	public ObjectResult moneyExchange(String currency, String record) {
		String token = request.getHeader("token");
		ObjectResult result = new ObjectResult();
		// 获取指定缓存对象
		Cache cache = getCache();
		// token验证
		String tokenVerify = tokenVerify(token, cache);
		if (!tokenVerify.equals(MessageUtil.SUCCESS)) {
			result.setCode(tokenVerify);
			return result;
		}
		String exchange = null;
		// 获取汇率
		if (currency.equals("AppleC")) {
			exchange = "1.00";
		} else {
			exchange = JsoupUtil.getExchange(currency);
		}
		if (exchange == null) {
			// 网络连接失败
			result.setCode(MessageUtil.NETWORK_CONNECTION);
			return result;
		}
		// 获取缓存中的数据
		Member member = (Member) cache.get(token).get();
		// 获取缓存中的会员id
		String mid = member.getMid();
		Map<String, Object> map = new HashMap<String, Object>();
		map.put("exchange", exchange);
		// 判断是否是首充
		if (record.equals("0")) {
			// 记录类型
			map.put("record", record);
			// 会员id
			map.put("mid", mid);
			// 根据会员id与记录类型查询是否有记录
			int total = memberService.loadFundRecordTotal(map);
			if (total == 0) {
				map.put("total", 0);
			} else {
				map.put("total", 1);
			}
			// 移除不需要返回的值
			map.remove("record");
			map.remove("mid");
			result.setResult(map);
			return result;
		} else if (record.equals("1")) {
			result.setResult(map);
			return result;
		}
		// 参数错误
		result.setCode(MessageUtil.PARAMETER_ERROR);
		return result;
	}

	/**
	 * 20180508在线取款
	 * 
	 * @param money
	 *            取款金额
	 * @param currency
	 *            货币
	 * @param phone
	 *            手机号
	 * @param address
	 *            钱包地址
	 * @param remark
	 *            备注
	 * @param password
	 *            取款密码
	 * @param currencyCount
	 *            货币个数
	 * @param withdrawnType
	 *            取款类型
	 * @return
	 */
	@RequestMapping(value = "member-withdrawn", method = RequestMethod.POST)
	@ResponseBody
	public ObjectResult memberWithdrawn(String money, String currency, String phone, String address, String remark,
			String password, String withdrawnType) {
		String token = request.getHeader("token");
		ObjectResult result = new ObjectResult();
		// 获取指定缓存对象
		Cache cache = getCache();
		// token验证
		String tokenVerify = tokenVerify(token, cache);
		if (!tokenVerify.equals(MessageUtil.SUCCESS)) {
			result.setCode(tokenVerify);
			return result;
		}
		if (withdrawnType.equals("0") && withdrawnType.equals("1")) {
			// 参数错误
			result.setCode(MessageUtil.PARAMETER_ERROR);
			return result;
		}
		// 获取缓存中的数据
		Member tokenMember = (Member) cache.get(token).get();
		// 获取缓存中的会员id
		String mid = tokenMember.getMid();
		String exchange = null;
		// 获取汇率
		if (currency.equals("AppleC")) {
			exchange = "1.00";
		} else {
			exchange = JsoupUtil.getExchange(currency);
			if (exchange == null) {
				// 网络连接失败
				result.setCode(MessageUtil.NETWORK_CONNECTION);
				return result;
			}
			// 利用字符串切割和替换，改变为纯数字
			String[] split = exchange.split(" ");
			exchange = split[0].replace(",", "");
		}
		// 换算金额
		Float currencyCount = Float.parseFloat(money) / Float.parseFloat(exchange);
		Map<String, Object> map = new HashMap<String, Object>();
		// 设置会员id
		map.put("mid", mid);
		List<Member> memberList = memberService.queryMember(map);
		if (memberList == null || memberList.size() > 1) {
			// 数据匹配错误
			result.setCode(MessageUtil.DATA_NOT_FOUND);
			return result;
		}
		Member member = memberList.get(0);
		// 判断取款密码是否正确
		if (!member.getBank_password().equals(password)) {
			// 密码错误
			result.setCode(MessageUtil.PASSWORD_ERROR);
			return result;
		}
		// 添加资金交易记录
		MemberFundRecord memberFundRecord = new MemberFundRecord();
		memberFundRecord.setFrid(BeanLoad.getId());// 资金交易id
		memberFundRecord.setMid(mid);// 会员id
		memberFundRecord.setNumber(BeanLoad.getNumber());// 订单号
		memberFundRecord.setTime(new Date());// 时间
		memberFundRecord.setPhone_code(phone);// 手机号
		memberFundRecord.setMoney_address(address);// 钱包地址
		memberFundRecord.setRecord("1");// 记录类型
		memberFundRecord.setWithdrawn_type(withdrawnType);// 取款类型
		memberFundRecord.setMoney(money);// 金额
		memberFundRecord.setCurrency(currency);// 充值类型
		memberFundRecord.setCurrency_count(String.format("%.3f", currencyCount));// 货币数量
		memberFundRecord.setDiscounts(null);// 优惠金额
		memberFundRecord.setState("0");// 状态
		memberFundRecord.setRemark(remark); // 备注
		map.put("memberFundRecord", memberFundRecord);
		int addFundRecord = memberService.memberWithdrawn(map);
		// 判断是否添加成功
		if (addFundRecord == 0) {
			// 添加失败
			result.setCode(MessageUtil.INSERT_ERROR);
			return result;
		}
		if (addFundRecord == -1) {
			// 余额不足
			result.setCode(MessageUtil.MONEY_EXCEED);
			return result;
		}
		return result;
	}

	/**
	 * 20180508在线存款
	 * 
	 * @param currency
	 *            货币类型
	 * @param currencyCount
	 *            货币个数
	 * @return
	 */
	@RequestMapping(value = "member-deposit", method = RequestMethod.POST)
	@ResponseBody
	public ObjectResult memberDeposit(String currency, String currencyCount) {
		String token = request.getHeader("token");
		ObjectResult result = new ObjectResult();
		// 获取指定缓存对象
		Cache cache = getCache();
		// token验证
		String tokenVerify = tokenVerify(token, cache);
		if (!tokenVerify.equals(MessageUtil.SUCCESS)) {
			result.setCode(tokenVerify);
			return result;
		}
		// 获取缓存中的数据
		Member tokenMember = (Member) cache.get(token).get();
		// 获取缓存中的会员id
		String mid = tokenMember.getMid();
		String exchange = null;
		// 获取汇率
		if (currency.equals("AppleC")) {
			exchange = "1.00";
		} else {
			exchange = JsoupUtil.getExchange(currency);
			if (exchange == null) {
				// 网络连接失败
				result.setCode(MessageUtil.NETWORK_CONNECTION);
				return result;
			}
			// 利用字符串切割和替换，改变为纯数字
			String[] split = exchange.split(" ");
			exchange = split[0].replace(",", "");
		}
		// 换算金额
		Float money = Float.parseFloat(exchange) * Float.parseFloat(currencyCount);
		Map<String, Object> map = new HashMap<String, Object>();
		map.put("record", "0");
		// 查询充值是否有记录
		int total = memberService.loadFundRecordTotal(map);
		Float discounts = null;
		// 判断是否存在首充
		if (total > 0) {
			discounts = money * 0.01f;
		} else {
			discounts = money * 0.01f + 188f;
		}
		// 生成订单号
		String number = BeanLoad.getNumber();
		// 添加资金交易记录
		MemberFundRecord memberFundRecord = new MemberFundRecord();
		memberFundRecord.setFrid(BeanLoad.getId());// 资金交易id
		memberFundRecord.setMid(mid);// 会员id
		memberFundRecord.setNumber(number);// 订单号
		memberFundRecord.setTime(new Date());// 时间
		memberFundRecord.setCurrency(currency);// 货币类型
		memberFundRecord.setCurrency_count(currencyCount);// 货币数量
		memberFundRecord.setRecord("0");// 记录类型
		memberFundRecord.setMoney(String.format("%.2f", money));// 交易金额
		memberFundRecord.setDiscounts(String.format("%.2f", discounts));// 优惠金额
		memberFundRecord.setState("0");// 状态
		int addFundRecord = memberService.addFundRecord(memberFundRecord);
		// 判断是否添加成功
		if (addFundRecord <= 0) {
			// 添加失败
			result.setCode(MessageUtil.INSERT_ERROR);
			return result;
		}
		Map<String, Object> resultMap = new HashMap<>();
		resultMap.put("number", number);
		resultMap.put("money", String.format("%.2f", money));
		resultMap.put("discounts", String.format("%.2f", discounts));
		result.setResult(resultMap);
		return result;
	}

	/**
	 * 20180516支付金额
	 * 
	 * @param member
	 */
	@RequestMapping(value = "member-pay", method = RequestMethod.POST)
	@ResponseBody
	public ObjectResult memberPay(String number) {
		String token = request.getHeader("token");
		ObjectResult result = new ObjectResult();
		// 获取指定缓存对象
		Cache cache = getCache();
		// token验证
		String tokenVerify = tokenVerify(token, cache);
		if (!tokenVerify.equals(MessageUtil.SUCCESS)) {
			result.setCode(tokenVerify);
			return result;
		}
		Map<String, Object> map = new HashMap<String, Object>();
		map.put("number", number);// 订单号
		List<MemberFundRecord> fundRecordList = memberService.queryFundRecord(map);
		MemberFundRecord fundRecord = fundRecordList.get(0);
		// 获得两个时间的毫秒时间差异
		long distance = new Date().getTime() - fundRecord.getTime().getTime();
		// 计算差多少天
		long day = distance / (1000 * 24 * 60 * 60);
		// 计算差多少小时
		long hour = (distance % (1000 * 24 * 60 * 60)) / (1000 * 60 * 60);
		// 计算差多少分
		long min = (distance % (1000 * 24 * 60 * 60) % (1000 * 60 * 60)) / (1000 * 60);
		// 计算差多少秒
		long second = (distance % (1000 * 24 * 60 * 60) % (1000 * 60 * 60) % (1000 * 60)) / 1000;
		// 计算总共相差多少秒
		long time = (day * 60 * 60 * 24) + (hour * 60 * 60) + (min * 60) + second;
		// 超过一天未完成支付则时间超时
		if (time > 60 * 60 * 24) {
			map.put("state", "-2");// 状态
			map.put("resultRemark", "订单失效，已超过24小时未完成支付！");
			// 根据订单号修改状态
			int updateFundRecord = memberService.updateFundRecord(map);
			if (updateFundRecord <= 0) {
				result.setCode(MessageUtil.UPDATE_ERROR);
				return result;
			}
			// 时间超时
			result.setCode(MessageUtil.TIME_OVERTIME);
			return result;
		} else {
			map.put("state", "1");// 状态
			// 根据订单号修改状态
			int updateFundRecord = memberService.updateFundRecord(map);
			if (updateFundRecord <= 0) {
				result.setCode(MessageUtil.UPDATE_ERROR);
				return result;
			}
			return result;
		}
	}

	/**
	 * 20180523充值/取款结算
	 * 
	 * @param number
	 *            订单号
	 * @param state
	 *            状态
	 * @param record
	 *            记录类型：充值、取款
	 * @return
	 */
	@RequestMapping(value = "alter/fund-record", method = RequestMethod.POST)
	@ResponseBody
	public ObjectResult alterFundRecord(String number, String state, String record, String resultRemark) {
		String token = request.getHeader("token");
		ObjectResult result = new ObjectResult();
		// 获取指定缓存对象
		Cache cache = getCache();
		// token验证
		String tokenVerify = tokenVerify(token, cache);
		if (!tokenVerify.equals(MessageUtil.SUCCESS)) {
			result.setCode(tokenVerify);
			return result;
		}
		Map<String, Object> map = new HashMap<String, Object>();
		map.put("number", number);// 订单号
		List<FundRecordDTO> queryFundRecordDTO = memberService.queryFundRecordDTO(map);
		if (queryFundRecordDTO.size() != 1 || queryFundRecordDTO == null) {
			// 空值
			result.setCode(MessageUtil.DATA_NOT);
			return result;
		}
		FundRecordDTO fundRecordDTO = queryFundRecordDTO.get(0);
		if (state.equals(fundRecordDTO.getState())) {
			// 订单已被结算
			result.setCode(MessageUtil.FUND_RECORD_ERROR);
			return result;
		}
		map.put("state", state);// 状态
		map.put("resultRemark", resultRemark);// 结果备注
		map.put("record", record);// 记录类型：充值、取款
		map.put("fundRecordDTO", fundRecordDTO);
		// 根据订单号修改状态
		int updateFundRecord = memberService.alterFundRecord(map);
		if (updateFundRecord <= 0) {
			result.setCode(MessageUtil.UPDATE_ERROR);
			return result;
		}
		return result;
	}

	/**
	 * 20180525充值/取款处理
	 * 
	 * @param number
	 *            订单号
	 * @param record
	 *            记录类型：充值、取款
	 * @return
	 */
	@RequestMapping(value = "dispose/fund-record", method = RequestMethod.POST)
	@ResponseBody
	public ObjectResult disposeFundRecord(String number, String record) {
		String token = request.getHeader("token");
		ObjectResult result = new ObjectResult();
		// 获取指定缓存对象
		Cache cache = getCache();
		// token验证
		String tokenVerify = tokenVerify(token, cache);
		if (!tokenVerify.equals(MessageUtil.SUCCESS)) {
			result.setCode(tokenVerify);
			return result;
		}
		// 获取缓存中的数据
		Member tokenMember = (Member) cache.get(token).get();
		// 获取缓存中的会员id
		String mid = tokenMember.getMid();
		Map<String, Object> map = new HashMap<String, Object>();
		map.put("number", number);// 订单号
		List<FundRecordDTO> queryFundRecordDTO = memberService.queryFundRecordDTO(map);
		if (queryFundRecordDTO.size() != 1 || queryFundRecordDTO == null) {
			// 空值
			result.setCode(MessageUtil.DATA_NOT);
			return result;
		}
		FundRecordDTO fundRecordDTO = queryFundRecordDTO.get(0);
		if (record.equals("0")) {
			if ("-1".equals(fundRecordDTO.getState())) {
				// 订单已被处理
				result.setCode(MessageUtil.FUND_RECORD_ERROR);
				return result;
			}
			map.put("state", "-1");// 状态
		} else if (record.equals("1")) {
			if ("1".equals(fundRecordDTO.getState())) {
				// 订单已被处理
				result.setCode(MessageUtil.FUND_RECORD_ERROR);
				return result;
			}
			map.put("state", "1");// 状态
		} else {
			// 参数错误
			result.setCode(MessageUtil.PARAMETER_ERROR);
			return result;
		}
		map.put("dispose", mid);
		// 根据订单号修改状态
		int updateFundRecord = memberService.updateFundRecord(map);
		if (updateFundRecord <= 0) {
			result.setCode(MessageUtil.UPDATE_ERROR);
			return result;
		}
		return result;
	}

	/**
	 * 20180525审核列表
	 * 
	 * @return
	 */
	@RequestMapping(value = "audit/fund-record", method = RequestMethod.GET)
	@ResponseBody
	public ObjectResult disposeFundRecord(String record) {
		String token = request.getHeader("token");
		ObjectResult result = new ObjectResult();
		// 获取指定缓存对象
		Cache cache = getCache();
		// token验证
		String tokenVerify = tokenVerify(token, cache);
		if (!tokenVerify.equals(MessageUtil.SUCCESS)) {
			result.setCode(tokenVerify);
			return result;
		}
		// 获取缓存中的数据
		Member tokenMember = (Member) cache.get(token).get();
		// 获取缓存中的会员id
		String mid = tokenMember.getMid();
		Map<String, Object> map = new HashMap<String, Object>();
		map.put("dispose", mid);// 处理人员
		map.put("record", record);// 存取款状态
		// 0表示充值记录，1表示提现记录
		if (record.equals("0")) {
			// 审核状态
			map.put("state", "-1");
		} else if (record.equals("1")) {
			// 审核状态
			map.put("state", "1");
		} else {
			// 参数错误
			result.setCode(MessageUtil.PARAMETER_ERROR);
			return result;
		}
		List<FundRecordDTO> list = memberService.queryFundRecordDTO(map);
		if (list == null || list.size() == 0) {
			return result;
		}
		MemberServiceImpl memberServiceImpl = new MemberServiceImpl();
		// 返回前端需要的字段
		List<Map<String, Object>> listByFundRecords = memberServiceImpl.toListByFundRecords(list, record, null);
		result.setResult(listByFundRecords);
		return result;
	}

	/**
	 * 20180509提现记录/充值记录
	 * 
	 * @param record
	 *            存取款记录
	 * @param beginTime
	 *            开始时间
	 * @param endTime
	 *            结束时间
	 * @param type
	 *            充值类型
	 * @param state
	 *            状态
	 * @param pageNo
	 *            当前页
	 * @param pageSize
	 *            每页显示条数
	 * @return
	 */
	@RequestMapping(value = "member-record", method = RequestMethod.POST)
	@ResponseBody
	public Page<Map<String, Object>> allRecord(String record, String beginTime, String endTime, String type,
			String state, Integer pageNo, Integer pageSize, String keyword) {
		String token = request.getHeader("token");
		Page<Map<String, Object>> page = new Page<Map<String, Object>>();
		// 获取指定缓存对象
		Cache cache = getCache();
		// token验证
		String tokenVerify = tokenVerify(token, cache);
		if (!tokenVerify.equals(MessageUtil.SUCCESS)) {
			page.setCode(tokenVerify);
			return page;
		}
		// 获取缓存中的数据
		Member tokenMember = (Member) cache.get(token).get();
		// 0表示充值记录，1表示提现记录
		if (!record.equals("0") && !record.equals("1")) {
			// 参数错误
			page.setCode(MessageUtil.PARAMETER_ERROR);
			return page;
		}
		// 获取缓存中的会员id
		String mid = tokenMember.getMid();
		Map<String, Object> map = new HashMap<String, Object>();
		map.put("mid", mid = tokenMember.getRole().equals("0") ? mid : null);// 会员id
		map.put("beginTime", StringUtils.isBlank(beginTime) ? null : beginTime);// 开始时间
		map.put("endTime", StringUtils.isBlank(endTime) ? null : endTime);// 结束时间
		map.put("type", type);// 充值类型
		map.put("state", state);// 状态
		map.put("keyword", keyword);// 关键字
		map.put("pageSize", pageSize);// 每页显示多少条
		map.put("beginIndex", pageNo == null || pageSize == null ? null : (pageNo - 1) * pageSize);// 下标
		map.put("pageNo", pageNo);// 当前页
		map.put("record", record);// 记录类型
		page = memberService.listFundRecord(map);
		if (page.getResult() == null) {
			// 未找到数据
			page.setCode(MessageUtil.DATA_NOT);
			return page;
		}
		return page;
	}

	/**
	 * 20180607查询推广返利
	 * 
	 * @param beginTime
	 *            开始时间
	 * @param endTime
	 *            结束时间
	 * @param pageNo
	 *            当前页
	 * @param pageSize
	 *            每页显示条数
	 * @return
	 */
	@RequestMapping(value = "generalize-rebate", method = RequestMethod.POST)
	@ResponseBody
	public Page<Map<String, Object>> generalizeRebate(String beginTime, String endTime, Integer pageNo,
			Integer pageSize, String keyword) {
		String token = request.getHeader("token");
		Page<Map<String, Object>> page = new Page<Map<String, Object>>();
		// 获取指定缓存对象
		Cache cache = getCache();
		// token验证
		String tokenVerify = tokenVerify(token, cache);
		if (!tokenVerify.equals(MessageUtil.SUCCESS)) {
			page.setCode(tokenVerify);
			return page;
		}
		// 获取缓存中的数据
		Member tokenMember = (Member) cache.get(token).get();
		// 获取缓存中的会员id
		String mid = tokenMember.getMid();
		Map<String, Object> map = new HashMap<String, Object>();
		map.put("mid", mid = tokenMember.getRole().equals("0") ? mid : null);// 会员id
		map.put("invitationCode", tokenMember.getName());// 邀请码
		map.put("state", "1");// 已结算才参与返利
		map.put("beginTime", StringUtils.isBlank(beginTime) ? null : beginTime);// 开始时间
		map.put("endTime", StringUtils.isBlank(endTime) ? null : endTime);// 结束时间
		map.put("keyword", keyword);// 关键字
		map.put("pageSize", pageSize);// 每页显示多少条
		map.put("beginIndex", pageNo == null || pageSize == null ? null : (pageNo - 1) * pageSize);// 下标
		map.put("pageNo", pageNo);// 当前页
		page = memberService.queryGeneralizeRebate(map);
		if (page.getResult() == null) {
			// 未找到数据
			page.setCode(MessageUtil.DATA_NOT);
			return page;
		}
		return page;
	}
	
	/**
	 * 20180526url地址加载
	 * 
	 * @param url
	 * @return
	 */
	@RequestMapping(value = "load-url", method = RequestMethod.POST)
	@ResponseBody
	public String loadUrl(String url) {
		// 根据url地址获取Document对象
		Document loadUrl = JsoupUtil.loadUrl(url);
		// 判断url地址是否加载失败
		if (loadUrl == null) {
			return MessageUtil.NETWORK_CONNECTION;
		}
		// 返回html网页
		return loadUrl.html();
	}

	/**
	 * 20180502会员下注
	 * 
	 * @param url
	 * @param gid
	 * @param ratio
	 *            赔率字段
	 * @param ratioData
	 *            赔率数据
	 * @param money
	 *            金额
	 * @param bet
	 *            下注对象，主客场
	 * @param betType
	 *            下注类型，足篮球
	 * @param iorRatio
	 *            比率字段
	 * @return
	 */
	@RequestMapping(value = "bet-member", method = RequestMethod.POST)
	@ResponseBody
	public ObjectResult betMember(String url, String gid, String ratio, String ratioData, String money, String bet,
			String betType, String iorRatio) {
		String token = request.getHeader("token");
		ObjectResult result = new ObjectResult();
		// 获取指定缓存对象
		Cache cache = getCache();
		// token验证
		String tokenVerify = tokenVerify(token, cache);
		if (!tokenVerify.equals(MessageUtil.SUCCESS)) {
			result.setCode(tokenVerify);
			return result;
		}
		// 判断下注金额是否超过规定的金额
		if (Float.parseFloat(money) < 10 || Float.parseFloat(money) > 10000) {
			// 超过下注的金额
			result.setCode(MessageUtil.MONEY_EXCEED);
			return result;
		}
		// 获取缓存中的数据
		Member member = (Member) cache.get(token).get();
		// 获取缓存中的会员id
		String mid = member.getMid();
		// 根据url地址获取所有数据
		String stringAll = JsoupUtil.getStringAll(url);
		if (StringUtils.isBlank(stringAll)) {
			// 网络连接失败
			result.setCode(MessageUtil.NETWORK_CONNECTION);
			return result;
		}
		// 根据所有数据进行切割获取数据
		List<Map<String, String>> list = JsoupUtil.listFieldAndData(stringAll);
		if (list == null) {
			// 空值
			result.setCode(MessageUtil.DATA_NOT);
			return result;
		}
		// 获取地址中与gid匹配的数据
		Map<String, String> mapData = JsoupUtil.getMapData(list, gid);
		if (mapData == null) {
			// 赛事结束不能下注
			result.setCode(MessageUtil.LEAGUE_END);
			return result;
		}
		// 查找数据判断赔率是否为空
		String mapRatio = mapData.get(ratio);
		if (StringUtils.isBlank(mapRatio)) {
			// 未找到匹配的数据
			result.setCode(MessageUtil.DATA_NOT_FOUND);
			return result;
		}
		// 根据查找的赔率判断误差，误差不能超过正负0.1
		Float margin = Float.parseFloat(mapRatio) - Float.parseFloat(ratioData);
		if (margin > 0.1 || margin < (-0.1)) {
			// 赔率误差过大
			result.setCode(MessageUtil.DATA_ERROR_OVERSIZE);
			return result;
		}
		Map<String, Object> map = new HashMap<String, Object>();
		map.put("mid", mid);
		// 查询会员余额是否够下注
		List<Member> memberList = memberService.queryMember(map);
		if (memberList == null || memberList.size() != 1) {
			// 数据匹配错误
			result.setCode(MessageUtil.DATA_NOT_FOUND);
			return result;
		}
		member = memberList.get(0);
		// 盘口字段
		String tapeField = iorRatio;
		// 获取比率
		if (StringUtils.isBlank(iorRatio) || mapData.get(iorRatio).equals("单") || mapData.get(iorRatio).equals("双")) {
			iorRatio = null;
		} else {
			// 获取比率
			iorRatio = mapData.get(iorRatio);
			// 判断比率是否存在字母
			iorRatio = iorRatio.contains("O") ? iorRatio.replace("O", "") : iorRatio;
			iorRatio = iorRatio.contains("U") ? iorRatio.replace("U", "") : iorRatio;
		}
		Map<String, List<String>> fieldExplain = JsoupUtil.getFieldExplain(stringAll);
		// 定义场次、比分、让球方
		String occasion = null, betScore = null, strong = null;
		// 判断是否是全场
		if (fieldExplain.get("fullList").contains(ratio)) {
			occasion = "全场";
			strong = mapData.get("strong");
			// 判断是否是让球
			if (ratio.equals("ior_RH") || ratio.equals("ior_RC")) {
				iorRatio = mapData.get("ratio");
			}
		} else if (fieldExplain.get("hrList").contains(ratio)) {
			occasion = "半场";
			strong = mapData.get("hstrong");
			// 判断是否是让球
			if (ratio.equals("ior_HRH") || ratio.equals("ior_HRC")) {
				iorRatio = mapData.get("hratio");
			}
		}
		// 定义比率类型
		String ratioType = JsoupUtil.ratioType(betType).get(ratio);
		// 判断是否是滚球
		if (betType.equals("FT")) {
			betType = url.contains("rtype=re") ? "REFT" : "RFT";
			betScore = betType.equals("REFT") ? mapData.get("score_h") + " - " + mapData.get("score_c") : null;
			if (betType.equals("REFT")) {
				List<String> reFootball = JsoupUtil.reFootball();
				if (reFootball.contains(ratio)) {
					occasion = "全场";
				}
			}
		} else if (betType.equals("BK")) {
			betType = url.contains("rtype=re") ? "REBK" : "RBK";
			betScore = betType.equals("REBK") ? mapData.get("score_h") + " - " + mapData.get("score_c") : null;
//			System.out.println(mapData.get("score_h") + " - " + mapData.get("score_c"));
//			System.out.println(mapData.get("score_H") + " - " + mapData.get("score_C") );
			// 判断是否有比分，有则是滚球
//			if (StringUtils.isNotBlank(mapData.get("score_h")) || StringUtils.isNotBlank(mapData.get("scoreH"))) {
//				occasion = "半场";
//			}
		} else {
			// 参数错误
			result.setCode(MessageUtil.PARAMETER_ERROR);
			return result;
		}
		// 计算有效金额
		float validMoney = Integer.parseInt(money) * Float.parseFloat(ratioData);
		String league = mapData.get("league");// 获取数据的赛事
		String teamh = mapData.get("team_h");// 获取数据的主场
		String teamc = mapData.get("team_c");// 获取数据的客场
		Date startTime = null;
		// 判断是否是滚动足球
		if (betType.equals("REFT")) {
			String dateTime = mapData.get("retimeset");// 获取数据的进行时间
			startTime = JsoupUtil.getRollFootball(dateTime);
		} else if (betType.equals("REBK")) {
			// 根据其他盘口获取时间
			Map<String, String> basketall= JsoupUtil.getRollBasketallData(list, league, teamh, teamc);
			String nowSession = basketall.get("nowSession");// 获取数据的第几节
			String lastTime = basketall.get("lastTime");// 获取数据比赛倒计时秒
			startTime = JsoupUtil.getRollBasketall(nowSession, lastTime);
		} else {
			String dateTime = mapData.get("datetime");
			startTime = JsoupUtil.getDataMapTime(dateTime);// 转换时间格式
		}
		String snid = BeanLoad.getId();// 随机生成主键id
		MemberSingleNote memberSingleNote = new MemberSingleNote();
		memberSingleNote.setSnid(snid);// 设置主键id
		memberSingleNote.setMid(mid);// 设置会员id
		memberSingleNote.setGid(gid);// 比赛gid
		memberSingleNote.setNumber(BeanLoad.getNumber());// 设置注单号
		memberSingleNote.setBet_time(new Date());// 设置时间
		memberSingleNote.setStart_time(startTime);// 设置开始比赛时间
		memberSingleNote.setType("体育");// 设置类型
		memberSingleNote.setTeam_h(teamh);// 设置主场
		memberSingleNote.setTeam_c(teamc);// 设置客场
		memberSingleNote.setBet_score(betScore == null ? "0 - 0" : betScore); // 设置下注比分
		memberSingleNote.setOccasion(occasion);// 设置场次
		memberSingleNote.setIor_type(ratioType);// 设置比率类型
		memberSingleNote.setIor_ratio(iorRatio);// 设置比率
		memberSingleNote.setTape_field(tapeField);// 设置盘口字段
		memberSingleNote.setRatio(ratioData);// 设置赔率
		memberSingleNote.setBet(bet);// 设置下注对象
		memberSingleNote.setBet_type(betType);// 设置下注类型
		memberSingleNote.setStrong(StringUtils.isBlank(strong) ? null : strong);// 让球方
		// 获取防护类型
		String defend = JsoupUtil.getDefend();
		String state = null;
		// 防护类型中存在需要防护的类型，则修改为下注中状态
		if(defend.contains(ratioType)) {
			state = ratioType.equals("单") || ratioType.equals("双") ? "0" : "-2";
		} else {
			state = "0";
		}
		memberSingleNote.setState(state);
		memberSingleNote.setLeague(league);// 设置联赛
		memberSingleNote.setMoney(money);// 设置下注金额
		memberSingleNote.setValid_money(String.format("%.2f", validMoney));// 设置有效金额
		map.put("memberSingleNote", memberSingleNote);
		int betMember = memberService.betMember(map);
		// 判断数据是否添加成功
		if (betMember == 0) {
			// 添加失败
			result.setCode(MessageUtil.INSERT_ERROR);
			return result;
		}
		if (betMember == -1) {
			// 超过自己的余额
			result.setCode(MessageUtil.MONEY_EXCEED);
			return result;
		}
		return result;
	}

	/**
	 * 20180517注单记录
	 * 
	 * @param keyword
	 *            关键字查询
	 * @param betType
	 *            下注类型足球|篮球
	 * @param beginTime
	 *            开始时间
	 * @param endTime
	 *            结束时间
	 * @param state
	 *            结算状态
	 * @param winLose
	 *            输赢状态
	 * @param pageNo
	 *            当前页
	 * @param pageSize
	 *            每页显示条数
	 * @return
	 */
	@RequestMapping(value = "single-note", method = RequestMethod.POST)
	@ResponseBody
	public Page<Map<String, Object>> allsingleNote(String keyword, String betType, String beginTime, String endTime,
			String state, String winLose, Integer pageNo, Integer pageSize) {
		String token = request.getHeader("token");
		Page<Map<String, Object>> page = new Page<Map<String, Object>>();
		// 获取指定缓存对象
		Cache cache = getCache();
		// token验证
		String tokenVerify = tokenVerify(token, cache);
		if (!tokenVerify.equals(MessageUtil.SUCCESS)) {
			page.setCode(tokenVerify);
			return page;
		}
		// 获取缓存中的数据
		Member tokenMember = (Member) cache.get(token).get();
		// 获取缓存中的会员id
		String mid = tokenMember.getMid();
		Map<String, Object> map = new HashMap<String, Object>();

		map.put("mid", mid = tokenMember.getRole().equals("0") ? mid : null);// 会员id
		map.put("beginTime", StringUtils.isBlank(beginTime) ? null : beginTime);// 开始时间
		map.put("endTime", StringUtils.isBlank(endTime) ? null : endTime);// 结束时间
		map.put("keyword", keyword);// 关键字查询
		map.put("betType", betType);// 下注类型足球|篮球
		map.put("state", state);// 结算状态
		map.put("winLose", winLose);// 输赢状态
		map.put("pageSize", pageSize);// 每页显示多少条
		map.put("beginIndex", pageNo == null || pageSize == null ? null : (pageNo - 1) * pageSize);// 下标
		map.put("pageNo", pageNo);// 当前页
		page = memberService.listSingleNote(map);
		if (page.getResult() == null) {
			// 未找到数据
			page.setCode(MessageUtil.DATA_NOT);
			return page;
		}
		return page;
	}

	/**
	 * 20180523注单取消
	 * 
	 * @param number
	 *            注单号
	 * @return
	 */
	@RequestMapping(value = "cancel/single-note", method = RequestMethod.POST)
	@ResponseBody
	public ObjectResult cancelSingleNote(String number) {
		String token = request.getHeader("token");
		ObjectResult result = new ObjectResult();
		// 获取指定缓存对象
		Cache cache = getCache();
		// token验证
		String tokenVerify = tokenVerify(token, cache);
		if (!tokenVerify.equals(MessageUtil.SUCCESS)) {
			result.setCode(tokenVerify);
			return result;
		}
		Map<String, Object> map = new HashMap<String, Object>();
		map.put("number", number);
		// 根据snid获取注单表详细信息
		List<SingleNoteDTO> querySingleNoteDTO = memberService.querySingleNoteDTO(map);
		if (querySingleNoteDTO.size() != 1 || querySingleNoteDTO == null) {
			result.setCode(MessageUtil.DATA_NOT);
			return result;
		}
		SingleNoteDTO singleNoteDTO = querySingleNoteDTO.get(0);
		if (!singleNoteDTO.getState().equals("0")) {
			// 注单取消错误
			result.setCode(MessageUtil.SINGLE_NOTE_ERROR);
			return result;
		}
		String money = singleNoteDTO.getMoney();// 获取下注金额
		String snid = singleNoteDTO.getSnid();// 获取注单id

		map.put("mid", singleNoteDTO.getMid());// 设置会员id
		map.put("money", String.format("%.2f", Float.parseFloat(money)));// 设置余额
		map.put("snid", snid);// 设置注单id
		map.put("state", "-1");
		// 根据mid修改余额
		int updateSum = memberService.cancelSingleNote(map);
		if (updateSum <= 0) {
			result.setCode(MessageUtil.UPDATE_ERROR);
			return result;
		}
		return result;
	}

	/**
	 * 20180523注单结算
	 * 
	 * @param number
	 *            注单号
	 * @param accident
	 *            意外情况：赛事腰折
	 * @param fullTeamh
	 *            全场主场得分
	 * @param fullTeamc
	 *            全场客场得分
	 * @param hrTeamh
	 *            半场主场得分
	 * @param hrTeamc
	 *            半场客场得分
	 * @return
	 */
	@RequestMapping(value = "account/single-note", method = RequestMethod.POST)
	@ResponseBody
	public ObjectResult accountSingleNote(String number, boolean accident, String fullTeamh, String fullTeamc,
			String hrTeamh, String hrTeamc) {
		String token = request.getHeader("token");
		ObjectResult result = new ObjectResult();
		// 获取指定缓存对象
		Cache cache = getCache();
		// token验证
		String tokenVerify = tokenVerify(token, cache);
		if (!tokenVerify.equals(MessageUtil.SUCCESS)) {
			result.setCode(tokenVerify);
			return result;
		}
		Map<String, Object> map = new HashMap<String, Object>();
		map.put("number", number);
		// 根据snid获取注单表详细信息
		List<SingleNoteDTO> querySingleNoteDTO = memberService.querySingleNoteDTO(map);
		if (querySingleNoteDTO.size() != 1 || querySingleNoteDTO == null) {
			result.setCode(MessageUtil.DATA_NOT);
			return result;
		}
		SingleNoteDTO singleNoteDTO = querySingleNoteDTO.get(0);
		String state = singleNoteDTO.getState();// 获取状态
		// 判断注单是否被结算
		if (state.equals("1")) {
			// 注单结算失败
			result.setCode(MessageUtil.SINGLE_NOTE_ERROR);
			return result;
		}
		// 如果赛事腰折,则直接结算
		if (accident) {
			String mid = singleNoteDTO.getMid();// 获取mid
			Float memberByMoney = Float.parseFloat(singleNoteDTO.getSum());// 获取用户余额
			Float money = Float.parseFloat(singleNoteDTO.getMoney());// 获取下注金额
			Float sum = memberByMoney + money;
			boolean stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNoteDTO, "0", "赛事腰斩");
			if (stateUpdate) {
				return result;
			}
		}
		String fullOrHrTeamc = null, fullOrHrTeamh = null;
		Integer scoreTeamc = null, scoreTeamh = null;
		String occasion = singleNoteDTO.getOccasion();// 获取注单表场次
		// 判断比赛是否是全场
		if (occasion.equals("全场")) {
			// 获取客场全场比分
			fullOrHrTeamc = fullTeamc;
			// 获取主场全场比分
			fullOrHrTeamh = fullTeamh;
		} else if (occasion.equals("半场")) {
			// 获取客场上半场比分
			fullOrHrTeamc = hrTeamc;
			// 获取主场上半场比分
			fullOrHrTeamh = hrTeamh;
		} else {
			result.setCode(MessageUtil.DATA_NOT);
			return result;
		}
		if (StringUtils.isBlank(fullOrHrTeamc) || StringUtils.isBlank(fullOrHrTeamh)) {
			result.setCode(MessageUtil.DATA_NOT);
			return result;
		}
		scoreTeamc = Integer.parseInt(fullOrHrTeamc);
		scoreTeamh = Integer.parseInt(fullOrHrTeamh);
		String amidithion = null;
		// 篮球比赛结果相反
		if (singleNoteDTO.getBet_type().equals("REBK") || singleNoteDTO.getBet_type().equals("RBK")) {
			amidithion = fullOrHrTeamc + " - " + fullOrHrTeamh;
		} else {
			amidithion = fullOrHrTeamh + " - " + fullOrHrTeamc;
		}
		;
		String betType = singleNoteDTO.getBet_type();// 获取下注类型
		// 判断是否是滚球
		if (betType.equals("REFT") || betType.equals("REBK")) {
			String getScore = singleNoteDTO.getBet_score();// 获取下注比分
			String[] split = getScore.split(" - ");
			scoreTeamh = scoreTeamh - Integer.valueOf(split[0]);
			scoreTeamc = scoreTeamc - Integer.valueOf(split[1]);
		}
		String bet = null;
		Integer score = null;
		// 比较主客场比分返回结果
		if (scoreTeamc > scoreTeamh) {
			bet = "C";
			score = scoreTeamc - scoreTeamh;
		} else if (scoreTeamc < scoreTeamh) {
			bet = "H";
			score = scoreTeamh - scoreTeamc;
		} else if (scoreTeamc == scoreTeamh) {
			bet = "N";
			score = scoreTeamh - scoreTeamc;
		}
		boolean stateUpdate = resultGame(singleNoteDTO, bet, scoreTeamh, scoreTeamc, score, amidithion);
		if (stateUpdate) {
			return result;
		} else {
			// 注单结算失败
			result.setCode(MessageUtil.SINGLE_NOTE_ERROR);
			return result;
		}
	}

	/**
	 * 20180605获取系统时间
	 * 
	 * @return
	 */
	@RequestMapping(value = "get-time", method = RequestMethod.POST)
	@ResponseBody
	public ObjectResult getDateTime() {
		ObjectResult result = new ObjectResult();
		// 获取系统时间
		Date date = new Date();
		// 时间格式化
		SimpleDateFormat format = new SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss");
		// 转换格式
		String time = format.format(date);
		result.setResult(time);
		return result;
	}

	/**
	 * 注单结算--定时任务
	 * 
	 * @return
	 * @throws ParseException
	 */
	public boolean singleNoteAccount() throws ParseException {
		// 创建map对象
		Map<String, Object> map = new HashMap<String, Object>();
		// 往map集合中添加数据
		map.put("state", "0");
		// 根据map在数据库中查询数据
		List<SingleNoteDTO> querySingleNoteById = memberService.querySingleNoteDTO(map);
		// 判断查询出来是否有数据
		if (querySingleNoteById == null || querySingleNoteById.size() <= 0) {
			System.out.println("没有注单结算的数据");
			return false;
		}
		// 使用迭代器循环遍历数据
		Iterator<SingleNoteDTO> iterator = querySingleNoteById.iterator();
		while (iterator.hasNext()) {
			SingleNoteDTO singleNote = iterator.next();
			if (singleNote == null) {
				continue;
			}
			// 获取下注时间
			Date betTime = singleNote.getBet_time();
			// 判断是足球联赛还是篮球联赛
			if (singleNote.getBet_type().equals("REFT") || singleNote.getBet_type().equals("RFT")) {
				// 根据联赛数据与时间获取足球数据
				List<Map<String, String>> footballResult = JsoupUtil.getFootballResult(singleNote.getLeague(), betTime);
				// 判断数据是否为空
				if (footballResult == null || footballResult.size() <= 0) {
					System.out.println(singleNote.getLeague() + "---没有注单结算的数据");
					continue;
				}
				// 足球数据匹配
				List<Object> listResult = JsoupUtil.getGameMap(footballResult, singleNote);
				if (listResult == null) {
					continue;
				}
				// 获取赢方
				String bet = (String) listResult.get(0);
				// 如果赛事腰斩了实行的操作
				if (bet.equals("赛事腰斩")) {
					boolean stateUpdate;
					String mid = singleNote.getMid();// 获取mid
					Float memberByMoney = Float.parseFloat(singleNote.getSum());// 获取用户余额
					Float money = Float.parseFloat(singleNote.getMoney());// 获取下注金额
					// 返回下注金额到账户余额
					Float sum = memberByMoney + money;
					stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "0", bet);
					if (stateUpdate) {
						System.out.println("注单结算成功");
						continue;
					}
				}
				// 获取赛果
				String amidithion = (String) listResult.get(1);
				// 获取客场比分
				Integer scoreTeamc = (Integer) listResult.get(2);
				// 获取主场比分
				Integer scoreTeamh = (Integer) listResult.get(3);
				// 获取比分差
				Integer score = (Integer) listResult.get(4);
				boolean stateUpdate = resultGame(singleNote, bet, scoreTeamh, scoreTeamc, score, amidithion);
				if (stateUpdate) {
					System.out.println("注单结算成功");
					continue;
				}
			} else if (singleNote.getBet_type().equals("RBK") || singleNote.getBet_type().equals("REBK")) {
				// 根据联赛数据与时间获取篮球数据
				List<Map<String, String>> basketballResult = JsoupUtil.getBasketballResult(singleNote.getLeague(),
						betTime);
				// 判断数据是否为空
				if (basketballResult == null || basketballResult.size() <= 0) {
					System.out.println(singleNote.getLeague() + "---没有注单结算的数据");
					continue;
				}
				// 篮球数据匹配
				List<Object> listResult = JsoupUtil.getGameMap(basketballResult, singleNote);
				if (listResult == null) {
					continue;
				}
				// 获取赢方
				String bet = (String) listResult.get(0);
				// 如果赛事腰斩了实行的操作
				if (bet.equals("赛事腰斩")) {
					boolean stateUpdate;
					String mid = singleNote.getMid();// 获取mid
					Float memberByMoney = Float.parseFloat(singleNote.getSum());// 获取用户余额
					Float money = Float.parseFloat(singleNote.getMoney());// 获取下注金额
					// 返回下注金额到账户余额
					Float sum = memberByMoney + money;
					stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "0", bet);
					if (stateUpdate) {
						System.out.println("注单结算成功");
						continue;
					}
				}
				// 获取赛果
				String amidithion = (String) listResult.get(1);
				// 获取客场比分
				Integer scoreTeamc = (Integer) listResult.get(2);
				// 获取主场比分
				Integer scoreTeamh = (Integer) listResult.get(3);
				// 获取比分差
				Integer score = (Integer) listResult.get(4);
				boolean stateUpdate = resultGame(singleNote, bet, scoreTeamh, scoreTeamc, score, amidithion);
				if (stateUpdate) {
					System.out.println("注单结算成功");
					continue;
				}
			}
		}
		return true;
	}

	/**
	 * 注单过期--定时任务
	 * 
	 * @return
	 */
	public boolean amidithionOvertime() {
		// 创建map对象
		Map<String, Object> map = new HashMap<String, Object>();
		// 往map集合中添加数据
		map.put("state", "0");
		// 根据map在数据库中查询数据
		List<SingleNoteDTO> querySingleNoteById = memberService.querySingleNoteDTO(map);
		// 判断查询出来是否有数据
		if (querySingleNoteById == null || querySingleNoteById.size() <= 0) {
			System.out.println("没有注单结算的数据");
			return false;
		}
		// 使用迭代器循环遍历数据
		Iterator<SingleNoteDTO> iterator = querySingleNoteById.iterator();
		while (iterator.hasNext()) {
			SingleNoteDTO singleNote = iterator.next();
			if (singleNote == null) {
				continue;
			}
			// 获得两个时间的毫秒时间差异
			long distance = new Date().getTime() - singleNote.getStart_time().getTime();
			// 计算差多少天
			long day = distance / (1000 * 24 * 60 * 60);
			// 计算差多少小时
			long hour = (distance % (1000 * 24 * 60 * 60)) / (1000 * 60 * 60);
			// 计算差多少分
			long min = (distance % (1000 * 24 * 60 * 60) % (1000 * 60 * 60)) / (1000 * 60);
			// 计算差多少秒
			long second = (distance % (1000 * 24 * 60 * 60) % (1000 * 60 * 60) % (1000 * 60)) / 1000;
			// 计算总共相差多少秒
			long time = (day * 60 * 60 * 24) + (hour * 60 * 60) + (min * 60) + second;
			// 如果注单超过4个小时未有结果就交给客服处理
			if (time <= 4 * 60 * 60) {
				continue;
			}
			Map<String, Object> singleNoteMap = new HashMap<String, Object>();
			// 根据snid修改数据
			singleNoteMap.put("snid", singleNote.getSnid());
			// 往map里添加需要修改的字段
			singleNoteMap.put("state", "2");
			// 根据snid修改注单状态
			int singleNoteAccount = memberService.singleNoteAccount(singleNoteMap);
			if (singleNoteAccount <= 0) {
				System.err.println("修改注单状态失败！");
				continue;
			} else {
				System.out.println("注单" + singleNote.getNumber() + "无赛果，移交给后台客服处理！");
			}
		}
		return true;
	}
	
	/**
	 * 结算返利金额
	 */
	public void accountRebate() {
		Map<String, Object> map = new HashMap<String, Object>();
		map.put("role", "0");
		// 查询所有会员信息
		List<Member> queryMember = memberService.queryMember(map);
		map.remove("role");
		// 根据时间获取前一天
		Date dBefore = JsoupUtil.previousDay(new Date());
		// 设置时间格式
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy年MM月");
		// 格式化前一天
		String previousDay = sdf.format(dBefore);
		map.put("betTime", previousDay);
		map.put("state", "1");// 已结算注单返利
		// 利用迭代器循环会员信息
		Iterator<Member> memberIterator = queryMember.iterator();
		while (memberIterator.hasNext()) {
			Member member = memberIterator.next();
			// 根据邀请码查询返利金额
			map.put("invitationCode", member.getName());
			List<SingleNoteDTO> generalizeRebate = memberService.generalizeRebate(map);
			// 判断会员是否有邀请的人
			if (generalizeRebate == null || generalizeRebate.size() <= 0) {
				continue;
			}
			Iterator<SingleNoteDTO> iterator = generalizeRebate.iterator();
			// 定义返利总和
			Float rebate = 0.00f;
			// 循环邀请的人数获取下注金额
			while (iterator.hasNext()) {
				SingleNoteDTO singleNoteDTO = iterator.next();
				Float money = Float.parseFloat(singleNoteDTO.getMoney());
				// 判断邀请的人是否存在没有下注
				if (money - 0 == 0) {
					continue;
				}
				rebate = rebate + money;
			}
			Map<String, Object> memberMap = new HashMap<String, Object>();
			memberMap.put("mid", member.getMid());// 会员id
			memberMap.put("money", String.format("%.2f", rebate * 0.01f));//金额
			memberMap.put("rebate", "1");// 返利标识
			int updateSum = memberService.updateSum(memberMap);
			if(updateSum <= 0) {
				System.err.println("返利结算出错！");
			}else {
				System.out.println("返利结算成功！");
			}
		}
	}
	
	/**
	 * 注单防护
	 */
	public void singleNoteDefend(){
		Map<String, Object> map = new HashMap<String, Object>();
		// 根据下注中查询所有注单
		map.put("state", "-2");
		// 防护类型
//		String iorTypes = JsoupUtil.getDefend();
//		map.put("iorTypes", iorTypes);
		List<MemberSingleNote> singleNotelist = memberService.querySingleNote(map);
		if(singleNotelist == null || singleNotelist.size() <= 0) {
			System.out.println("需要防护的数据不存在");
		}else {
			map.remove("iorTypes");
			map.remove("state");
			// 循环遍历下注中的数据
			Iterator<MemberSingleNote> iterator = singleNotelist.iterator();
			while(iterator.hasNext()) {
				MemberSingleNote singleNote = iterator.next();
				String snid = singleNote.getSnid();// 获取注单id
				map.put("snid", snid);// 设置注单id
				if(singleNote.getIor_type().equals("单") || singleNote.getIor_type().equals("双")) {
					String money = singleNote.getMoney();// 获取下注金额
					map.put("mid", singleNote.getMid());// 设置会员id
					map.put("money", String.format("%.2f", Float.parseFloat(money)));// 设置余额
					map.put("state", "-1");
					// 根据mid修改余额
					memberService.cancelSingleNote(map);
				}
				// 获得两个时间的毫秒时间差异
				long distance = new Date().getTime() - singleNote.getBet_time().getTime();
				// 计算差多少天
				long day = distance / (1000 * 24 * 60 * 60);
				// 计算差多少小时
				long hour = (distance % (1000 * 24 * 60 * 60)) / (1000 * 60 * 60);
				// 计算差多少分
				long min = (distance % (1000 * 24 * 60 * 60) % (1000 * 60 * 60)) / (1000 * 60);
				// 计算差多少秒
				long second = (distance % (1000 * 24 * 60 * 60) % (1000 * 60 * 60) % (1000 * 60)) / 1000;
				// 计算总共相差多少秒
				long time = (day * 60 * 60 * 24) + (hour * 60 * 60) + (min * 60) + second;
				// 判断下注时间是否有60秒
				if(time < 60) {
					continue;
				}
				String gid = singleNote.getGid();// 获取比赛gid
				String type = singleNote.getBet_type();// 获取下注类型
				// 根据下注类型获取url地址
				String url = JsoupUtil.getUrl(type);
				// 根据url地址获取所有数据
				String stringAll = JsoupUtil.getStringAll(url);

				// 根据所有数据进行切割获取数据
				List<Map<String, String>> list = JsoupUtil.listFieldAndData(stringAll);
				if (list == null) {
					System.err.println("数据切割错误！");
					continue;
				}
				// 获取地址中与gid匹配的数据
				Map<String, String> mapData = JsoupUtil.getMapData(list, gid);
				if (mapData == null) {
					String money = singleNote.getMoney();// 获取下注金额
					map.put("mid", singleNote.getMid());// 设置会员id
					map.put("money", String.format("%.2f", Float.parseFloat(money)));// 设置余额
					map.put("state", "-1");
					// 根据mid修改余额
					memberService.cancelSingleNote(map);
				} else {
					// 获取比率
					String iorRatio = mapData.get(singleNote.getTape_field());
					// 判断比率是否存在字母
					iorRatio = iorRatio.contains("O") ? iorRatio.replace("O", "") : iorRatio;
					iorRatio = iorRatio.contains("U") ? iorRatio.replace("U", "") : iorRatio;
					// 匹配
					if(iorRatio.equals(singleNote.getIor_ratio())) {
						map.put("state", "0");
						memberService.singleNoteAccount(map);
					}else {
						String money = singleNote.getMoney();// 获取下注金额
						map.put("mid", singleNote.getMid());// 设置会员id
						map.put("money", String.format("%.2f", Float.parseFloat(money)));// 设置余额
						map.put("state", "-1");
						// 根据mid修改余额
						memberService.cancelSingleNote(map);
					}
				}
			}
		}
	}
	
	/**
	 * 足球篮球结算
	 * 
	 * @param singleNote
	 *            注单DTO
	 * @param listResult
	 *            足球篮球list数据
	 * @return
	 */
	public boolean resultGame(SingleNoteDTO singleNote, String bet, Integer scoreTeamh, Integer scoreTeamc,
			Integer score, String amidithion) {
		Map<String, Object> memberMap = new HashMap<String, Object>();
		boolean stateUpdate;
		String mid = singleNote.getMid();// 获取会员id
		memberMap.put("mid", mid);
		// 根据会员id查询会员所有信息
		List<Member> memberList = memberService.queryMember(memberMap);
		if (memberList == null || memberList.size() > 1) {
			System.err.println("mid查询不到数据");
		}
		Member member = memberList.get(0);
		Float memberByMoney = Float.parseFloat(member.getSum());// 得到账户余额
		Float money = Float.parseFloat(singleNote.getMoney());// 得到下注金额
		Float validMoney = Float.parseFloat(singleNote.getValid_money());// 得到有效金额
		Float sum = null;
		// 获取下注比率
		String ratio = singleNote.getIor_ratio();
		// 获取比率类型
		String iorType = singleNote.getIor_type();
		if (iorType.equals("单")) {
			// 全输
			if ((scoreTeamc + scoreTeamh) % 2 == 0) {
				sum = memberByMoney;
				stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "-1", amidithion);
				return stateUpdate ? true : false;
			}
			// 全赢
			if ((scoreTeamc + scoreTeamh) % 2 == 1) {
				sum = memberByMoney + validMoney;
				stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1", amidithion);
				return stateUpdate ? true : false;
			}
		}
		if (iorType.equals("双")) {
			// 全赢
			if ((scoreTeamc + scoreTeamh) % 2 == 0) {
				sum = memberByMoney + validMoney;
				stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1", amidithion);
				return stateUpdate ? true : false;
			}
			// 全输
			if ((scoreTeamc + scoreTeamh) % 2 == 1) {
				sum = memberByMoney;
				stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "-1", amidithion);
				return stateUpdate ? true : false;
			}
		}
		if (iorType.equals("大")) {
			// 根据比率计算大输赢
			if (ratio.contains("/") && ratio.contains(".")) {
				String[] split = ratio.split("/");
				if (split[0].contains(".")) {
					// 既不是大球也不是小球，买大球的人赢一半
					if ((scoreTeamc + scoreTeamh) - Integer.parseInt(split[1]) == 0) {
						sum = memberByMoney + money + (validMoney / 2);
						stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1",
								amidithion);
						return stateUpdate ? true : false;
					}
					// 全输
					if ((scoreTeamc + scoreTeamh) < Integer.parseInt(split[1])) {
						sum = memberByMoney;
						stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "-1",
								amidithion);
						return stateUpdate ? true : false;
					}
					// 全赢
					if ((scoreTeamc + scoreTeamh) > Integer.parseInt(split[1])) {
						sum = memberByMoney + money + validMoney;
						stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1",
								amidithion);
						return stateUpdate ? true : false;
					}
				} else if (split[1].contains(".")) {
					// 既不是大球也不是小球，买大球的人输一半
					if ((scoreTeamc + scoreTeamh) - Integer.parseInt(split[0]) == 0) {
						sum = memberByMoney + (money / 2);
						stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "-1",
								amidithion);
						return stateUpdate ? true : false;
					}
					// 全输
					if ((scoreTeamc + scoreTeamh) < Integer.parseInt(split[0])) {
						sum = memberByMoney;
						stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "-1",
								amidithion);
						return stateUpdate ? true : false;
					}
					// 全赢
					if ((scoreTeamc + scoreTeamh) > Integer.parseInt(split[0])) {
						sum = memberByMoney + money + validMoney;
						stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1",
								amidithion);
						return stateUpdate ? true : false;
					}
				}
			} else if (ratio.contains(".") && !ratio.contains("/")) {
				// 全赢
				if ((scoreTeamc + scoreTeamh) > Float.parseFloat(ratio)) {
					sum = memberByMoney + money + validMoney;
					stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1",
							amidithion);
					return stateUpdate ? true : false;
				}
				// 全输
				if ((scoreTeamc + scoreTeamh) < Float.parseFloat(ratio)) {
					sum = memberByMoney;
					stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "-1",
							amidithion);
					return stateUpdate ? true : false;
				}
			} else if (!ratio.contains(".") && !ratio.contains("/")) {
				// 全赢
				if ((scoreTeamc + scoreTeamh) > Integer.parseInt(ratio)) {
					sum = memberByMoney + money + validMoney;
					stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1",
							amidithion);
					return stateUpdate ? true : false;
				}
				// 全输
				if ((scoreTeamc + scoreTeamh) < Integer.parseInt(ratio)) {
					sum = memberByMoney;
					stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "-1",
							amidithion);
					return stateUpdate ? true : false;
				}
				// 不输不赢
				if ((scoreTeamc + scoreTeamh) - Integer.parseInt(ratio) == 0) {
					sum = memberByMoney + money;
					stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "0",
							amidithion);
					return stateUpdate ? true : false;
				}
			}
		}
		if (iorType.equals("小")) {
			// 根据比率计算小输赢
			if (ratio.contains("/") && ratio.contains(".")) {
				String[] split = ratio.split("/");
				if (split[0].contains(".")) {
					// 既不是大球也不是小球，买小球的人输一半
					if ((scoreTeamc + scoreTeamh) - Integer.parseInt(split[1]) == 0) {
						sum = memberByMoney + (money / 2);
						stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "-1",
								amidithion);
						return stateUpdate ? true : false;
					}
					// 全输
					if ((scoreTeamc + scoreTeamh) > Integer.parseInt(split[1])) {
						sum = memberByMoney;
						stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "-1",
								amidithion);
						return stateUpdate ? true : false;
					}
					// 全赢
					if ((scoreTeamc + scoreTeamh) < Integer.parseInt(split[1])) {
						sum = memberByMoney + money + validMoney;
						stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1",
								amidithion);
						return stateUpdate ? true : false;
					}
				} else if (split[1].contains(".")) {
					// 既不是大球也不是小球，买小球的人赢一半
					if ((scoreTeamc + scoreTeamh) - Integer.parseInt(split[0]) == 0) {
						sum = memberByMoney + money + (validMoney / 2);
						stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1",
								amidithion);
						return stateUpdate ? true : false;
					}
					// 全输
					if ((scoreTeamc + scoreTeamh) > Integer.parseInt(split[0])) {
						sum = memberByMoney;
						stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "-1",
								amidithion);
						return stateUpdate ? true : false;
					}
					// 全赢
					if ((scoreTeamc + scoreTeamh) < Integer.parseInt(split[0])) {
						sum = memberByMoney + money + validMoney;
						stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1",
								amidithion);
						return stateUpdate ? true : false;
					}
				}
			} else if (ratio.contains(".") && !ratio.contains("/")) {
				// 全赢
				if ((scoreTeamc + scoreTeamh) < Float.parseFloat(ratio)) {
					sum = memberByMoney + money + validMoney;
					stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1",
							amidithion);
					return stateUpdate ? true : false;
				}
				// 全输
				if ((scoreTeamc + scoreTeamh) > Float.parseFloat(ratio)) {
					sum = memberByMoney;
					stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "-1",
							amidithion);
					return stateUpdate ? true : false;
				}
			} else if (!ratio.contains(".") && !ratio.contains("/")) {
				// 全赢
				if ((scoreTeamc + scoreTeamh) < Integer.parseInt(ratio)) {
					sum = memberByMoney + money + validMoney;
					stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1",
							amidithion);
					return stateUpdate ? true : false;
				}
				// 全输
				if ((scoreTeamc + scoreTeamh) > Integer.parseInt(ratio)) {
					sum = memberByMoney;
					stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "-1",
							amidithion);
					return stateUpdate ? true : false;
				}
				// 不输不赢
				if ((scoreTeamc + scoreTeamh) - Integer.parseInt(ratio) == 0) {
					sum = memberByMoney + money;
					stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "0",
							amidithion);
					return stateUpdate ? true : false;
				}
			}
		}
		if (iorType.equals("单大")) {
			if (singleNote.getBet().equals("H")) {
				if (ratio.contains(".")) {
					// 全赢
					if (scoreTeamh > Float.parseFloat(ratio)) {
						sum = memberByMoney + money + validMoney;
						stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1",
								amidithion);
						return stateUpdate ? true : false;
					}
					// 全输
					if (scoreTeamh < Float.parseFloat(ratio)) {
						sum = memberByMoney;
						stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "-1",
								amidithion);
						return stateUpdate ? true : false;
					}
				} else if (!ratio.contains(".")) {
					// 全赢
					if (scoreTeamh > Integer.parseInt(ratio)) {
						sum = memberByMoney + money + validMoney;
						stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1",
								amidithion);
						return stateUpdate ? true : false;
					}
					// 全输
					if (scoreTeamh < Integer.parseInt(ratio)) {
						sum = memberByMoney;
						stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "-1",
								amidithion);
						return stateUpdate ? true : false;
					}
					// 不输不赢
					if (scoreTeamh - Integer.parseInt(ratio) == 0) {
						sum = memberByMoney + money;
						stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "0",
								amidithion);
						return stateUpdate ? true : false;
					}
				}
			}
			if (singleNote.getBet().equals("C")) {
				if (ratio.contains(".")) {
					// 全赢
					if (scoreTeamc > Float.parseFloat(ratio)) {
						sum = memberByMoney + money + validMoney;
						stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1",
								amidithion);
						return stateUpdate ? true : false;
					}
					// 全输
					if (scoreTeamc < Float.parseFloat(ratio)) {
						sum = memberByMoney;
						stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "-1",
								amidithion);
						return stateUpdate ? true : false;
					}
				} else if (!ratio.contains(".")) {
					// 全赢
					if (scoreTeamc > Integer.parseInt(ratio)) {
						sum = memberByMoney + money + validMoney;
						stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1",
								amidithion);
						return stateUpdate ? true : false;
					}
					// 全输
					if (scoreTeamc < Integer.parseInt(ratio)) {
						sum = memberByMoney;
						stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "-1",
								amidithion);
						return stateUpdate ? true : false;
					}
					// 不输不赢
					if (scoreTeamc - Integer.parseInt(ratio) == 0) {
						sum = memberByMoney + money;
						stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "0",
								amidithion);
						return stateUpdate ? true : false;
					}
				}
			}
		}
		if (iorType.equals("单小")) {
			if (singleNote.getBet().equals("H")) {
				if (ratio.contains(".")) {
					// 全赢
					if (scoreTeamh < Float.parseFloat(ratio)) {
						sum = memberByMoney + money + validMoney;
						stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1",
								amidithion);
						return stateUpdate ? true : false;
					}
					// 全输
					if (scoreTeamh > Float.parseFloat(ratio)) {
						sum = memberByMoney;
						stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "-1",
								amidithion);
						return stateUpdate ? true : false;
					}
				} else if (!ratio.contains(".")) {
					// 全赢
					if (scoreTeamh < Integer.parseInt(ratio)) {
						sum = memberByMoney + money + validMoney;
						stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1",
								amidithion);
						return stateUpdate ? true : false;
					}
					// 全输
					if (scoreTeamh > Integer.parseInt(ratio)) {
						sum = memberByMoney;
						stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "-1",
								amidithion);
						return stateUpdate ? true : false;
					}
					// 不输不赢
					if (scoreTeamh - Integer.parseInt(ratio) == 0) {
						sum = memberByMoney + money;
						stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "0",
								amidithion);
						return stateUpdate ? true : false;
					}
				}
			}
			if (singleNote.getBet().equals("C")) {
				if (ratio.contains(".")) {
					// 全赢
					if (scoreTeamc < Float.parseFloat(ratio)) {
						sum = memberByMoney + money + validMoney;
						stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1",
								amidithion);
						return stateUpdate ? true : false;
					}
					// 全输
					if (scoreTeamc > Float.parseFloat(ratio)) {
						sum = memberByMoney;
						stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "-1",
								amidithion);
						return stateUpdate ? true : false;
					}
				} else if (!ratio.contains(".")) {
					// 全赢
					if (scoreTeamc < Integer.parseInt(ratio)) {
						sum = memberByMoney + money + validMoney;
						stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1",
								amidithion);
						return stateUpdate ? true : false;
					}
					// 全输
					if (scoreTeamc > Integer.parseInt(ratio)) {
						sum = memberByMoney;
						stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "-1",
								amidithion);
						return stateUpdate ? true : false;
					}
					// 不输不赢
					if (scoreTeamc - Integer.parseInt(ratio) == 0) {
						sum = memberByMoney + money;
						stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "0",
								amidithion);
						return stateUpdate ? true : false;
					}
				}
			}
		}
		if (iorType.equals("让球") || iorType.equals("让分")) {
			if (singleNote.getBet().equals("H")) {
				if (singleNote.getStrong().equals(singleNote.getBet())) {
					// 根据比率计算让球输赢
					if (ratio.contains("/") && ratio.contains(".")) {
						String[] split = ratio.split("/");
						if (split[0].contains(".")) {
							// 赢一半
							if (scoreTeamh - Integer.parseInt(split[1]) == scoreTeamc) {
								sum = memberByMoney + money + (validMoney / 2);
								stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1",
										amidithion);
								return stateUpdate ? true : false;
							}
							// 全输
							if (scoreTeamh - Integer.parseInt(split[1]) < scoreTeamc) {
								sum = memberByMoney;
								stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote,
										"-1", amidithion);
								return stateUpdate ? true : false;
							}
							// 全赢
							if (scoreTeamh - Integer.parseInt(split[1]) > scoreTeamc) {
								sum = memberByMoney + money + validMoney;
								stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1",
										amidithion);
								return stateUpdate ? true : false;
							}
						} else if (split[1].contains(".")) {
							// 输一半
							if (scoreTeamh - Integer.parseInt(split[0]) == scoreTeamc) {
								sum = memberByMoney + (money / 2);
								stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote,
										"-1", amidithion);
								return stateUpdate ? true : false;
							}
							// 全输
							if (scoreTeamh - Integer.parseInt(split[0]) < scoreTeamc) {
								sum = memberByMoney;
								stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote,
										"-1", amidithion);
								return stateUpdate ? true : false;
							}
							// 全赢
							if (scoreTeamh - Integer.parseInt(split[0]) > scoreTeamc) {
								sum = memberByMoney + money + validMoney;
								stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1",
										amidithion);
								return stateUpdate ? true : false;
							}
						}
					} else if (ratio.contains(".") && !ratio.contains("/")) {
						// 全输
						if (scoreTeamh - (Float.parseFloat(ratio)) < scoreTeamc) {
							sum = memberByMoney;
							stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "-1",
									amidithion);
							return stateUpdate ? true : false;
						}
						// 全赢
						if (scoreTeamh - (Float.parseFloat(ratio)) > scoreTeamc) {
							sum = memberByMoney + money + validMoney;
							stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1",
									amidithion);
							return stateUpdate ? true : false;
						}
					} else if (!ratio.contains(".") && !ratio.contains("/")) {
						// 全输
						if (scoreTeamh - (Integer.parseInt(ratio)) < scoreTeamc) {
							sum = memberByMoney;
							stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "-1",
									amidithion);
							return stateUpdate ? true : false;
						}
						// 全赢
						if (scoreTeamh - (Integer.parseInt(ratio)) > scoreTeamc) {
							sum = memberByMoney + money + validMoney;
							stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1",
									amidithion);
							return stateUpdate ? true : false;
						}
						// 不输不赢
						if (scoreTeamh - (Integer.parseInt(ratio)) == scoreTeamc) {
							sum = memberByMoney + money;
							stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "0",
									amidithion);
							return stateUpdate ? true : false;
						}
					}
				}
				if (!singleNote.getStrong().equals(singleNote.getBet())) {
					// 根据比率计算让球输赢
					if (ratio.contains("/") && ratio.contains(".")) {
						String[] split = ratio.split("/");
						if (split[0].contains(".")) {
							// 输一半
							if (scoreTeamh + Integer.parseInt(split[1]) == scoreTeamc) {
								sum = memberByMoney + (money / 2);
								stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote,
										"-1", amidithion);
								return stateUpdate ? true : false;
							}
							// 全赢
							if (scoreTeamh + Integer.parseInt(split[1]) > scoreTeamc) {
								sum = memberByMoney + money + validMoney;
								stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1",
										amidithion);
								return stateUpdate ? true : false;
							}
							// 全输
							if (scoreTeamh + Integer.parseInt(split[1]) < scoreTeamc) {
								sum = memberByMoney;
								stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote,
										"-1", amidithion);
								return stateUpdate ? true : false;
							}
						} else if (split[1].contains(".")) {
							// 赢一半
							if (scoreTeamh + Integer.parseInt(split[0]) == scoreTeamc) {
								sum = memberByMoney + money + (validMoney / 2);
								stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1",
										amidithion);
								return stateUpdate ? true : false;
							}
							// 全赢
							if (scoreTeamh + Integer.parseInt(split[0]) > scoreTeamc) {
								sum = memberByMoney + money + validMoney;
								stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1",
										amidithion);
								return stateUpdate ? true : false;
							}
							// 全输
							if (scoreTeamh + Integer.parseInt(split[0]) < scoreTeamc) {
								sum = memberByMoney;
								stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote,
										"-1", amidithion);
								return stateUpdate ? true : false;
							}
						}
					} else if (ratio.contains(".") && !ratio.contains("/")) {
						// 全赢
						if (scoreTeamh + Float.parseFloat(ratio) > scoreTeamc) {
							sum = memberByMoney + money + validMoney;
							stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1",
									amidithion);
							return stateUpdate ? true : false;
						}
						// 全输
						if (scoreTeamh + Float.parseFloat(ratio) < scoreTeamc) {
							sum = memberByMoney;
							stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "-1",
									amidithion);
							return stateUpdate ? true : false;
						}
					} else if (!ratio.contains(".") && !ratio.contains("/")) {
						// 全赢
						if (scoreTeamh + Integer.parseInt(ratio) > scoreTeamc) {
							sum = memberByMoney + money + validMoney;
							stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1",
									amidithion);
							return stateUpdate ? true : false;
						}
						// 全输
						if (scoreTeamh + Integer.parseInt(ratio) < scoreTeamc) {
							sum = memberByMoney;
							stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "-1",
									amidithion);
							return stateUpdate ? true : false;
						}
						// 不输不赢
						if (scoreTeamh + Integer.parseInt(ratio) == scoreTeamc) {
							sum = memberByMoney + money;
							stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "0",
									amidithion);
							return stateUpdate ? true : false;
						}
					}
				}
			}
			if (singleNote.getBet().equals("C")) {
				if (singleNote.getStrong().equals(singleNote.getBet())) {
					// 根据比率计算让球输赢
					if (ratio.contains("/") && ratio.contains(".")) {
						String[] split = ratio.split("/");
						if (split[0].contains(".")) {
							// 赢一半
							if (scoreTeamc - Integer.parseInt(split[1]) == scoreTeamh) {
								sum = memberByMoney + money + (validMoney / 2);
								stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1",
										amidithion);
								return stateUpdate ? true : false;
							}
							// 全输
							if (scoreTeamc - Integer.parseInt(split[1]) < scoreTeamh) {
								sum = memberByMoney;
								stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote,
										"-1", amidithion);
								return stateUpdate ? true : false;
							}
							// 全赢
							if (scoreTeamc - Integer.parseInt(split[1]) > scoreTeamh) {
								sum = memberByMoney + money + validMoney;
								stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1",
										amidithion);
								return stateUpdate ? true : false;
							}
						} else if (split[1].contains(".")) {
							// 输一半
							if (scoreTeamc - Integer.parseInt(split[0]) == scoreTeamh) {
								sum = memberByMoney + (money / 2);
								stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote,
										"-1", amidithion);
								return stateUpdate ? true : false;
							}
							// 全输
							if (scoreTeamc - Integer.parseInt(split[0]) < scoreTeamh) {
								sum = memberByMoney;
								stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote,
										"-1", amidithion);
								return stateUpdate ? true : false;
							}
							// 全赢
							if (scoreTeamc - Integer.parseInt(split[0]) > scoreTeamh) {
								sum = memberByMoney + money + validMoney;
								stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1",
										amidithion);
								return stateUpdate ? true : false;
							}
						}
					} else if (ratio.contains(".") && !ratio.contains("/")) {
						// 全输
						if (scoreTeamc - (Float.parseFloat(ratio)) < scoreTeamh) {
							sum = memberByMoney;
							stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "-1",
									amidithion);
							return stateUpdate ? true : false;
						}
						// 全赢
						if (scoreTeamc - (Float.parseFloat(ratio)) > scoreTeamh) {
							sum = memberByMoney + money + validMoney;
							stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1",
									amidithion);
							return stateUpdate ? true : false;
						}
					} else if (!ratio.contains(".") && !ratio.contains("/")) {
						// 全输
						if (scoreTeamc - (Integer.parseInt(ratio)) < scoreTeamh) {
							sum = memberByMoney;
							stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "-1",
									amidithion);
							return stateUpdate ? true : false;
						}
						// 全赢
						if (scoreTeamc - (Integer.parseInt(ratio)) > scoreTeamh) {
							sum = memberByMoney + money + validMoney;
							stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1",
									amidithion);
							return stateUpdate ? true : false;
						}
						// 不输不赢
						if (scoreTeamc - (Integer.parseInt(ratio)) == scoreTeamh) {
							sum = memberByMoney + money;
							stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "0",
									amidithion);
							return stateUpdate ? true : false;
						}
					}
				}
				if (!singleNote.getStrong().equals(singleNote.getBet())) {
					// 根据比率计算让球输赢
					if (ratio.contains("/") && ratio.contains(".")) {
						String[] split = ratio.split("/");
						if (split[0].contains(".")) {
							// 输一半
							if (scoreTeamc + Integer.parseInt(split[1]) == scoreTeamh) {
								sum = memberByMoney + (money / 2);
								stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote,
										"-1", amidithion);
								return stateUpdate ? true : false;
							}
							// 全赢
							if (scoreTeamc + Integer.parseInt(split[1]) > scoreTeamh) {
								sum = memberByMoney + money + validMoney;
								stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1",
										amidithion);
								return stateUpdate ? true : false;
							}
							// 全输
							if (scoreTeamc + Integer.parseInt(split[1]) < scoreTeamh) {
								sum = memberByMoney;
								stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote,
										"-1", amidithion);
								return stateUpdate ? true : false;
							}
						} else if (split[1].contains(".")) {
							// 赢一半
							if (scoreTeamc + Integer.parseInt(split[0]) == scoreTeamh) {
								sum = memberByMoney + money + (validMoney / 2);
								stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1",
										amidithion);
								return stateUpdate ? true : false;
							}
							// 全赢
							if (scoreTeamc + Integer.parseInt(split[0]) > scoreTeamh) {
								sum = memberByMoney + money + validMoney;
								stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1",
										amidithion);
								return stateUpdate ? true : false;
							}
							// 全输
							if (scoreTeamc + Integer.parseInt(split[0]) < scoreTeamh) {
								sum = memberByMoney;
								stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote,
										"-1", amidithion);
								return stateUpdate ? true : false;
							}
						}
					} else if (ratio.contains(".") && !ratio.contains("/")) {
						// 全赢
						if (scoreTeamc + Float.parseFloat(ratio) > scoreTeamh) {
							sum = memberByMoney + money + validMoney;
							stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1",
									amidithion);
							return stateUpdate ? true : false;
						}
						// 全输
						if (scoreTeamc + Float.parseFloat(ratio) < scoreTeamh) {
							sum = memberByMoney;
							stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "-1",
									amidithion);
							return stateUpdate ? true : false;
						}
					} else if (!ratio.contains(".") && !ratio.contains("/")) {
						// 全赢
						if (scoreTeamc + Integer.parseInt(ratio) > scoreTeamh) {
							sum = memberByMoney + money + validMoney;
							stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1",
									amidithion);
							return stateUpdate ? true : false;
						}
						// 全输
						if (scoreTeamc + Integer.parseInt(ratio) < scoreTeamh) {
							sum = memberByMoney;
							stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "-1",
									amidithion);
							return stateUpdate ? true : false;
						}
						// 不输不赢
						if (scoreTeamc + Integer.parseInt(ratio) == scoreTeamh) {
							sum = memberByMoney + money;
							stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "0",
									amidithion);
							return stateUpdate ? true : false;
						}
					}
				}
			}
		}
		// 如果赛事下注赢实行的操作
		if (bet.equals(singleNote.getBet())) {
			if (iorType.equals("独赢")) {
				// 下注金额与有效金额都返回账户余额
				sum = memberByMoney + validMoney;
				stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "1", amidithion);
				return stateUpdate ? true : false;
			}
		}
		// 如果赛事下注输实行的操作
		if (!bet.equals(singleNote.getBet())) {
			// 返回下注金额到账户余额
			sum = memberByMoney;
			stateUpdate = memberService.stateUpdate(mid, sum, memberByMoney, money, singleNote, "-1", amidithion);
			return stateUpdate ? true : false;
		}
		return false;
	}

	/**
	 * token验证
	 * 
	 * @param token
	 * @param cache
	 * @return
	 */
	public String tokenVerify(String token, Cache cache) {
		// 判断token是否为空
		if (StringUtils.isBlank(token)) {
			// 空值
			return MessageUtil.NULL_ERROR;
		}
		// 判断token是否过期
		if (cache.get(token) == null) {
			// token过期
			return MessageUtil.TOKEN_OVERDUE;
		}
		// 获取缓存中的数据
		Member tokenMember = (Member) cache.get(token).get();
		// 判断是否在其他地方登录
		if (cache.get(tokenMember.getMid()) == null) {
			// 移除token
			cache.evict(token);
			// 登录会员冲突
			return MessageUtil.LOGIN_MEMBER_OUT;
		}
		if (cache.get(tokenMember.getAddress()) == null || !token.equals(tokenMember.getToken())) {
			// 移除token
			cache.evict(token);
			// 登录IP冲突
			return MessageUtil.LOGIN_IP_OUT;
		}
		return MessageUtil.SUCCESS;
	}

	/**
	 * 获取IP地址
	 * 
	 * @return
	 */
	public String getIpAddress() {
		// 根据请求头x-forwarded-for信息获取IP地址
		String ipAddress = request.getHeader("x-forwarded-for");
		// 判断IP地址是否为unknown或者为空，换为请求头Proxy-Client-IP信息
		if (ipAddress == null || ipAddress.length() == 0 || "unknown".equalsIgnoreCase(ipAddress)) {
			ipAddress = request.getHeader("Proxy-Client-IP");
		}
		// 判断IP地址是否为unknown或者为空，换为请求头WL-Proxy-Client-IP信息
		if (ipAddress == null || ipAddress.length() == 0 || "unknown".equalsIgnoreCase(ipAddress)) {
			ipAddress = request.getHeader("WL-Proxy-Client-IP");
		}
		// 判断IP地址是否为unknown或者为空，换为获取客户端的IP地址的方法
		if (ipAddress == null || ipAddress.length() == 0 || "unknown".equalsIgnoreCase(ipAddress)) {
			ipAddress = request.getRemoteAddr();
			// 判断IP是否为真实IP，如果不是则根据网卡获取配置IP
			if (ipAddress.equals("127.0.0.1") || ipAddress.equals("0:0:0:0:0:0:0:1")) {
				// 根据网卡取本机配置的IP
				InetAddress inet = null;
				try {
					inet = InetAddress.getLocalHost();
				} catch (UnknownHostException e) {
					// IP地址获取失败！
					return MessageUtil.IP_ADDRESS;
				}
				ipAddress = inet.getHostAddress();
			}
		}
		// 对于通过多个代理的情况，第一个IP为客户端真实IP,多个IP按照','分割
		if (ipAddress != null && ipAddress.length() > 15) {
			if (ipAddress.indexOf(",") > 0) {
				ipAddress = ipAddress.substring(0, ipAddress.indexOf(","));
			}
		}
		return ipAddress;
	}

	/**
	 * 指定一个ehcache缓存
	 * 
	 * @return
	 */
	public Cache getCache() {
		Cache cache = manager.getCache("memberCache");
		return cache;
	}

	/**
	 * List<Member> 转为 List<Map<String, Object>> 只保留前端需要的字段
	 * 
	 * @param dtos
	 * @return
	 */
	public List<Map<String, Object>> toMapByMembers(List<Member> dtos, String token) {
		List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
		if (dtos != null) {
			for (Member dto : dtos) {
				Map<String, Object> map = toMapByMember(dto, token);
				list.add(map);
			}
		}
		return list;
	}
	
	/**
	 * Member 放入 Map<String, Object> 只保留前端需要的字段
	 * 
	 * @param member
	 * @return
	 */
	private Map<String, Object> toMapByMember(Member member, String token) {
		Map<String, Object> map = new HashMap<String, Object>();
		map.put("mid", member.getMid());// mid
		map.put("name", member.getName());// 姓名
		map.put("address", member.getAddress());// ip地址
		map.put("real_name", member.getReal_name());// 真实姓名
		if(token == null) {
			SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm");
			String date = format.format(member.getRegister_time());
			map.put("registerTime", date);// 注册时间
			map.put("rebate", member.getRebate());// 返利钱包
			map.put("sum", member.getSum());// 本地钱包
			map.put("invitationCode", member.getInvitation_code());// 邀请码
		} else {
			map.put("token", token); // token
		}
		return map;
	}
}
