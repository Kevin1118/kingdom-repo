package com.kingdom.consultant.service.impl;

import com.alibaba.dubbo.config.annotation.Service;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.kingdom.commonutils.CommonUtils;
import com.kingdom.commonutils.Constant;
import com.kingdom.commonutils.RedisKeyUtil;
import com.kingdom.dao.*;
import com.kingdom.dto.user.ReturnDetailDTO;
import com.kingdom.interfaceservice.consultant.ConsultantService;
import com.kingdom.pojo.*;
import com.kingdom.result.ResultCode;
import com.kingdom.vojo.product.OrderVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;


import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;


/**
 * @author : long
 * @date : 2020/6/20 12:48
 */
@Service
public class ConsultantServiceImpl implements ConsultantService, Constant {

    @Autowired
    private ConsultantMapper consultantMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private PropertyMapper propertyMapper;

    @Autowired
    private UserMapper userMapper;


    /**
     * 投顾人注册
     *
     * @param consultant 投顾人信息
     * @return 响应码
     */
    @Override
    public ResultCode register(Consultant consultant) {


        //判断邮箱是否被注册
        Consultant c = consultantMapper.selectByEmail(consultant.getEmail());
        if (c != null) {
            return ResultCode.REGISTER_EMAIL_ERROR;
        }

        //生成密码salt值，取5位
        String salt = CommonUtils.generateUUID().substring(0, 5);
        consultant.setPasswordsalt(salt);
        //设置时间
        consultant.setCreatetime((int) (System.currentTimeMillis() / 1000));
        //设置默认状态为0，未激活
        consultant.setStatus(0);
        //生成激活码
        consultant.setActivationcode(CommonUtils.generateUUID());


        //将密码和salt值拼接后进行md5加密
        consultant.setPassword(CommonUtils.md5(consultant.getPassword() + salt));
        //设置默认头像，使用牛客网默认头像
        consultant.setAvatar(String.format("http://images.nowcoder.com/head/%dt.png", new Random().nextInt(1000)));
        //设置默认简介
        consultant.setDescription("一位神秘的投资精英");
        int result = consultantMapper.insertConsultant(consultant);
        //如果操作行数为1，则返回成功，否则返回数据库操作错误码
        if (result == 1) {
            return ResultCode.SUCCESS;
        } else {
            return ResultCode.MYSQL_CURD_ERROR;
        }
    }

    /**
     * 投顾人登录
     *
     * @param email    邮箱号
     * @param password 密码
     * @return map格式数据，响应码和登录凭证
     */
    @Override
    public Map<String, Object> login(String email, String password) {
        //通过邮箱查询用户
        Consultant consultant = consultantMapper.selectByEmail(email);
        //map存储响应码和loginTicket
        Map<String, Object> map = new HashMap<>(2);

        //检查账号是否存在,账号状态
        if (consultant == null) {
            map.put("resultCode", ResultCode.LOGIN_EMAIL_ERROR);
            return map;
        }
        if (consultant.getStatus() == 1) {
            map.put("resultCode", ResultCode.LOGIN_STATUS_ERROR);
            return map;
        }

        //检查密码
        //密码加salt进行md5加密
        password = CommonUtils.md5(password + consultant.getPasswordsalt());
        if (!consultant.getPassword().equals(password)) {
            map.put("resultCode", ResultCode.LOGIN_PWD_ERROR);
            return map;
        }
        //校验通过，生成登录凭证
        LoginTicket ticket = new LoginTicket();
        ticket.setTicket(CommonUtils.generateUUID());
        ticket.setUserid(consultant.getConsultantid());
        ticket.setStatus(0);
        //凭证有效期十二个小时
        ticket.setExpired((int) (System.currentTimeMillis() / 1000 + 3600 * 12));
        String redisKey = RedisKeyUtil.getTicketKey(ticket.getTicket());
        //将ticket存到redis数据库中
        redisTemplate.opsForValue().set(redisKey, ticket);
        //返回响应码数据和登录凭证
        map.put("resultCode", ResultCode.SUCCESS);
        map.put("loginTicket", ticket);
        return map;
    }


    /**
     * 更新头像
     *
     * @param consultantId 投顾人id
     * @param avatarUrl    头像链接
     * @return 响应码
     */
    @Override
    public ResultCode updateAvatar(int consultantId, String avatarUrl) {
        int rows = consultantMapper.updateAvatar(consultantId, avatarUrl);
        if (rows == 1) {
            //如果操作行数为1，则清楚redis缓存，返回成功状态码
            clearCache(consultantId);
            return ResultCode.SUCCESS;
        } else {
            return ResultCode.MYSQL_CURD_ERROR;
        }
    }


    /**
     * 根据登录凭证查找凭证详细信息
     *
     * @param loginTicket 登录凭证值
     * @return 登录凭证对象
     */
    @Override
    public LoginTicket findLoginTicket(String loginTicket) {
        //从redis中取loginTicket对象
        String redisKey = RedisKeyUtil.getTicketKey(loginTicket);
        return (LoginTicket) redisTemplate.opsForValue().get(redisKey);
    }

    /**
     * 根据投顾id查找投顾对象
     *
     * @param consultantId 投顾id
     * @return 投顾对象
     */
    @Override
    public Consultant findConsultantById(int consultantId) {
        //先从redis缓存中查找对象
        Consultant consultant = getCache(consultantId);
        //如果缓存中没有，则调用缓存初始化方法从mysql读取
        if (consultant == null) {
            consultant = initCache(consultantId);
        }
        return consultant;
    }

    /**
     * 实名认证
     *
     * @param consultantId 投顾人id
     * @param name         姓名
     * @param idNumber     身份证号
     * @return 响应码
     */
    @Override
    public ResultCode updateNameAndId(int consultantId, String name, String idNumber) {
        int rows = consultantMapper.updateNameAndId(consultantId, name, idNumber);
        //操作行数为1则清空redis缓存并返回成功响应码
        if (rows == 1) {
            clearCache(consultantId);
            return ResultCode.SUCCESS;
        } else {
            //返回数据库操作错误响应码
            return ResultCode.MYSQL_CURD_ERROR;
        }
    }

    /**
     * 更新支付密码
     *
     * @param consultant     投顾人对象
     * @param oldPayPassword 旧支付密码
     * @param newPayPassword 新支付密码
     * @return 响应码
     */
    @Override
    public ResultCode updatePayPassword(Consultant consultant, String oldPayPassword, String newPayPassword) {
        //将旧密码与旧salt拼接后md5加密，与数据库中原加密后的密码比对
        if (CommonUtils.md5(oldPayPassword + consultant.getPaypasswordsalt()).equals(consultant.getPaypassword())) {
            //生成新的salt值
            String salt = CommonUtils.generateUUID().substring(0, 5);
            int rows = consultantMapper.updatePayPassword(consultant.getConsultantid(), CommonUtils.md5(newPayPassword + salt), salt);
            //更新数据库成功则清空redis并返回成功响应码，否则返回数据库操作失败
            if (rows == 1) {
                clearCache(consultant.getConsultantid());
                return ResultCode.SUCCESS;
            } else {
                return ResultCode.MYSQL_CURD_ERROR;
            }
        } else {
            //返回密码错误
            return ResultCode.UPDATE_PWD_ERROR;
        }

    }

    /**
     * 设置支付密码
     *
     * @param consultantId 投顾人id
     * @param payPassword  支付密码
     * @return 响应码
     */
    @Override
    public ResultCode setPayPassword(int consultantId, String payPassword) {
        //生成salt值
        String salt = CommonUtils.generateUUID().substring(0, 5);
        //加密处理密码
        payPassword = CommonUtils.md5(payPassword + salt);
        int rows = consultantMapper.updatePayPassword(consultantId, payPassword, salt);
        if (rows == 1) {
            clearCache(consultantId);
            return ResultCode.SUCCESS;
        } else {
            return ResultCode.MYSQL_CURD_ERROR;
        }
    }


    /**
     * 查询产品
     *
     * @param pageNum      页码
     * @param pageSize     分页大小
     * @param consultantId 投顾id
     * @return 产品列表
     */
    @Override
    public Map selectProduct(int pageNum, int pageSize, int consultantId) {
        //分页组件
        Page<Object> pageObject = PageHelper.startPage(pageNum, pageSize);
        //查询投顾人所属产品列表
        List<Product> selectProductList = productMapper.selectProductByConsultantId(consultantId);
        //产品统计列表
        List<HashMap> productCountList = new ArrayList<>();
        //查询出每个产品的统计信息
        for (Product product : selectProductList) {
            int productId = product.getProductid();
            HashMap productRedis = getProductCache(productId);
            if (productRedis == null) {
                productRedis = initProductCache(productId);
            }
            productCountList.add(productRedis);
        }
        Map map = new HashMap(3);
        map.put("total", pageObject.getTotal());
        map.put("data", selectProductList);
        map.put("count", productCountList);
        return map;
    }

    /**
     * 从redis缓存中取对象
     *
     * @param consultantId 投顾人id
     * @return 投顾人对象
     */
    private Consultant getCache(int consultantId) {
        String redisKey = RedisKeyUtil.getConsultantKey(consultantId);
        return (Consultant) redisTemplate.opsForValue().get(redisKey);
    }


    /**
     * 缓存中没有投顾对象时初始化缓存对象
     *
     * @param consultantId 投顾人id
     * @return 投顾人对象
     */
    private Consultant initCache(int consultantId) {
        Consultant consultant = consultantMapper.selectById(consultantId);
        String redisKey = RedisKeyUtil.getConsultantKey(consultantId);
        redisTemplate.opsForValue().set(redisKey, consultant, 3600, TimeUnit.SECONDS);
        return consultant;
    }

    /**
     * 当数据变更时清除缓存数据
     *
     * @param consultantId 投顾人id
     */
    private void clearCache(int consultantId) {
        String redisKey = RedisKeyUtil.getConsultantKey(consultantId);
        redisTemplate.delete(redisKey);
    }

    /**
     * 从缓存中获取product对象
     *
     * @param productId productId
     */
    private HashMap getProductCache(int productId) {
        String redisKey = RedisKeyUtil.getProductKey(productId);
        return (HashMap) redisTemplate.opsForValue().get(redisKey);
    }

    /**
     * 当天无购买时初始化0
     *
     * @param productId
     * @return
     */
    private HashMap initProductCache(int productId) {
        Product productMysql = productMapper.selectProductById(productId);
        HashMap product = new HashMap(16);
        product.put("productId", productId);
        product.put("productName", productMysql.getName());
        product.put("stockAmount", productMysql.getStockamount());
        product.put("fundAmount", productMysql.getFundamount());
        product.put("expectedYield", productMysql.getExpectedYield());
        product.put("peopleCount", 0);
        product.put("moneyCount", 0);
        String redisKey = RedisKeyUtil.getProductKey(productId);
        redisTemplate.opsForValue().set(redisKey, product);
        return product;
    }


    /**
     * 查询订单
     *
     * @param pageNum      页码
     * @param pageSize     分页大小
     * @param consultantId 投顾id
     * @return 订单审批列表
     */
    @Override
    public Map selectOrders(int pageNum, int pageSize, int consultantId, int type) {
        //分页
        Page<Object> pageObject = PageHelper.startPage(pageNum, pageSize);
        List<Order> buyList;
        List<Order> sellList;
        Map map = new HashMap(3);
        if (type == APPROVAL) {
            //查询买入审批列表
            buyList = orderMapper.selectOrderByConsultantIdAndStatus(consultantId, APPROVAL_BUY);
            List<OrderVo> buyListVo = new ArrayList<>();
            for (Order order : buyList) {
                HashMap product = getProductCache(order.getProductid());
                String productName = product.get("productName").toString();
                BigDecimal expected = (BigDecimal) product.get("expectedYield");
                float expectedYield = (float) (order.getSum() * expected.floatValue() * 0.01f);

                OrderVo orderVo = new OrderVo();
                orderVo.setId(order.getId());
                orderVo.setOrderid(order.getOrderid());
                orderVo.setProductid(order.getProductid());
                orderVo.setSum((float) order.getSum());
                orderVo.setProductname(productName);
                orderVo.setExpectedyield(expectedYield);
                orderVo.setStatus(order.getStatus());
                orderVo.setTransactiondate(order.getTransactiondate());
                buyListVo.add(orderVo);
            }
            //查询卖出审批列表
            sellList = orderMapper.selectOrderByConsultantIdAndStatus(consultantId, APPROVAL_SELL);
            List<OrderVo> sellListVo = new ArrayList<>();
            for (Order order : sellList) {
                HashMap product = getProductCache(order.getProductid());
                String productName = product.get("productName").toString();
                BigDecimal expected = (BigDecimal) product.get("expectedYield");
                float expectedYield = (float) (order.getSum() * expected.floatValue() * 0.01f);
                OrderVo orderVo = new OrderVo();
                orderVo.setId(order.getId());
                orderVo.setOrderid(order.getOrderid());
                orderVo.setProductid(order.getProductid());
                orderVo.setSum((float) order.getSum());
                orderVo.setProductname(productName);
                orderVo.setExpectedyield(expectedYield);
                orderVo.setStatus(order.getStatus());
                orderVo.setTransactiondate(order.getTransactiondate());
                sellListVo.add(orderVo);
            }
            map.put("buyApproval", buyListVo);
            map.put("sellApproval", sellListVo);

        } else {
            buyList = orderMapper.selectOrderByConsultantIdAndStatus(consultantId, WAIT_TO_BUY);
            List<OrderVo> buyTransactionListVo = new ArrayList<>();
            for (Order order : buyList) {
                HashMap product = getProductCache(order.getProductid());
                String productName = product.get("productName").toString();
                BigDecimal expected = (BigDecimal) product.get("expectedYield");
                float expectedYield = (float) (order.getSum() * expected.floatValue() * 0.01f);
                OrderVo orderVo = new OrderVo();
                orderVo.setId(order.getId());
                orderVo.setOrderid(order.getOrderid());
                orderVo.setProductid(order.getProductid());
                orderVo.setSum((float) order.getSum());
                orderVo.setProductname(productName);
                orderVo.setExpectedyield(expectedYield);
                orderVo.setStatus(order.getStatus());
                orderVo.setTransactiondate(order.getTransactiondate());
                buyTransactionListVo.add(orderVo);
            }
            sellList = orderMapper.selectOrderByConsultantIdAndStatus(consultantId, WAIT_TO_SELL);
            List<OrderVo> sellTransactionListVo = new ArrayList<>();
            for (Order order : sellList) {
                HashMap product = getProductCache(order.getProductid());
                String productName = product.get("productName").toString();
                BigDecimal expected = (BigDecimal) product.get("expectedYield");
                float expectedYield = (float) (order.getSum() * expected.floatValue() * 0.01f);
                OrderVo orderVo = new OrderVo();
                orderVo.setId(order.getId());
                orderVo.setOrderid(order.getOrderid());
                orderVo.setProductid(order.getProductid());
                orderVo.setSum((float) order.getSum());
                orderVo.setProductname(productName);
                orderVo.setExpectedyield(expectedYield);
                orderVo.setStatus(order.getStatus());
                orderVo.setTransactiondate(order.getTransactiondate());
                sellTransactionListVo.add(orderVo);
            }
            map.put("buyTransaction", buyTransactionListVo);
            map.put("sellTransaction", sellTransactionListVo);
        }

        return map;
    }


    @Override
    public ResultCode updateOrderStatus(List<Order> orders) {
        for (Order order : orders) {
            int id = order.getId();
            int productId = order.getProductid();
            int sum = (int) order.getSum();
            int status = order.getStatus();
            //判断当前审批订单是买入还是卖出，并修改为对应的状态

            if (status == APPROVAL_BUY) {
                orderMapper.updateOrderStatus(id, WAIT_TO_BUY);
                HashMap product = getProductCache(productId);
                if (product == null) {
                    product = initProductCache(productId);
                }
                    int peopleCount = (int) product.get("peopleCount");
                    int moneyCount = (int) product.get("moneyCount");
                    product.put("peopleCount", peopleCount + 1);
                    product.put("moneyCount", moneyCount + sum);
                    redisTemplate.opsForValue().set(RedisKeyUtil.getProductKey(productId), product);
                insertRecord(order,"产品买入",null,(int) order.getSum());
            } else if (status == APPROVAL_SELL){
                orderMapper.updateOrderStatus(id, WAIT_TO_SELL);
                insertRecord(order,"产品卖出",null,(int) order.getSum());
            }

        }
        return ResultCode.SUCCESS;
    }

    /**
     * 买入基金和股票
     *
     * @param ids 订单
     * @return 响应码
     */
    @Override
    public ResultCode buyStockAndFund(List<Integer> ids) {
        //查出所有订单详情。
        List<Order> orders = orderMapper.selectByIds(ids);
        for (Order order : orders) {
            if (order.getStatus() != WAIT_TO_BUY) {
                continue;
            }
            float oriSum = (float) order.getSum();
            //查询每个订单对应的产品
            Product product = productMapper.selectProductById(order.getProductid());
            //查询每个订单对应的基金和股票明细
            List<ProductStockDetail> stockList = productMapper.selectStockProportionFromDetail(order.getProductid());
            List<ProductFundDetail> fundList = productMapper.selectFundProportionFromDetail(order.getProductid());
//            获取所有股票的代码,并获取股票当前价格
            List<String> stockCodes = new ArrayList<>();
            for (ProductStockDetail detail : stockList) {
                stockCodes.add(detail.getStockCode());
            }
            //如果不为空，查询股票价格
            if (!stockCodes.isEmpty()) {
                List<StockAlternate> stockPrices = productMapper.selectStockAlternate(stockCodes);
                for (int i = 0; i < stockList.size(); i++) {
                    float sum = oriSum * product.getStockamount() * stockList.get(i).getProportion().floatValue() * 0.01f;
                    float price = stockPrices.get(i).getValueNow().floatValue() * STOCK_AMOUNT_LIMIT;
                    if (sum >= price) {
                        Property propertyMysql = propertyMapper.loadPropertyByCode(order.getAccountno(), stockList.get(i).getStockCode());
                        int amount = Math.round(sum / price);
                        order.setSum(order.getSum() - amount * price / STOCK_AMOUNT_LIMIT);
                        if (propertyMysql == null) {
                            Property property = new Property();
                            property.setSignaccountid(order.getAccountno());
                            property.setOrderid(order.getOrderid());
                            property.setUserid(order.getUserid());
                            property.setType("股票");
                            property.setCode(stockList.get(i).getStockCode());
                            property.setPropertyname(stockList.get(i).getStockName());
                            property.setAmount(amount);
                            property.setUpdatetime((int) (System.currentTimeMillis() / 1000));
                            property.setStatus(0);
                            propertyMapper.insertProperty(property);
                            insertRecord(order, "股票买入", property, amount * price / STOCK_AMOUNT_LIMIT);
                        } else {
                            propertyMysql.setAmount(propertyMysql.getAmount() + amount);
                            propertyMysql.setUpdatetime((int) (System.currentTimeMillis() / 1000));
                            propertyMapper.updatePropertyAmount(propertyMysql.getPropertyid(), propertyMysql.getAmount(), propertyMysql.getUpdatetime());
                            propertyMysql.setAmount(amount);
                            insertRecord(order, "股票买入", propertyMysql, amount * price / STOCK_AMOUNT_LIMIT);
                        }
                    }


                }
            }

            //获取所有基金的代码，并获取基金当前价格
            List<String> fundCodes = new ArrayList<>();
            for (ProductFundDetail detail : fundList) {
                fundCodes.add(detail.getFundCode());
            }
            List<FundAlternate> fundPrices;
            //如果不为空，查询基金价格
            if (!fundCodes.isEmpty()) {
                fundCodes.add(MONEY_FUND_CODE);
                fundPrices = productMapper.selectFundAlternate(fundCodes);
                for (int i = 0; i < fundList.size(); i++) {
                    float sum = oriSum * product.getFundamount() * fundList.get(i).getProportion().floatValue() * 0.01f;
                    float price = fundPrices.get(i).getValueNow().floatValue() * FUND_AMOUNT_LIMIT;
                    if (sum >= price) {
                        Property propertyMysql = propertyMapper.loadPropertyByCode(order.getAccountno(), fundList.get(i).getFundCode());
                        int amount = Math.round(sum / price);
                        order.setSum(order.getSum() - amount * price / FUND_AMOUNT_LIMIT);
                        if (propertyMysql == null) {
                            Property property = new Property();
                            property.setSignaccountid(order.getAccountno());
                            property.setOrderid(order.getOrderid());
                            property.setUserid(order.getUserid());
                            property.setType("基金");
                            property.setCode(fundList.get(i).getFundCode());
                            property.setPropertyname(fundList.get(i).getFundName());
                            property.setAmount(amount);
                            property.setUpdatetime((int) (System.currentTimeMillis() / 1000));
                            property.setStatus(0);
                            propertyMapper.insertProperty(property);
                            insertRecord(order, "基金买入", property, amount * price / STOCK_AMOUNT_LIMIT);
                        } else {
                            propertyMysql.setAmount(propertyMysql.getAmount() + amount);
                            propertyMysql.setUpdatetime((int) (System.currentTimeMillis() / 1000));
                            propertyMapper.updatePropertyAmount(propertyMysql.getPropertyid(), propertyMysql.getAmount(), propertyMysql.getUpdatetime());
                            propertyMysql.setAmount(amount);
                            insertRecord(order, "基金买入", propertyMysql, amount * price / STOCK_AMOUNT_LIMIT);
                        }
                    }

                }
            } else {
                fundCodes.add(MONEY_FUND_CODE);
                fundPrices = productMapper.selectFundAlternate(fundCodes);
            }

            //剩余金额购买货币基金
            if (order.getSum() > 0) {
                int amount = (int) Math.round(order.getSum() / fundPrices.get(fundPrices.size() - 1).getValueNow().floatValue());
                Property propertyMysql = propertyMapper.loadPropertyByCode(order.getAccountno(), MONEY_FUND_CODE);
                if (propertyMysql == null) {
                    Property property = new Property();
                    property.setSignaccountid(order.getAccountno());
                    property.setOrderid(order.getOrderid());
                    property.setUserid(order.getUserid());
                    property.setType("货币市场型");
                    property.setCode(MONEY_FUND_CODE);
                    property.setPropertyname(MONEY_FUND_NAME);
                    property.setAmount(amount);
                    property.setUpdatetime((int) (System.currentTimeMillis() / 1000));
                    property.setStatus(0);
                    propertyMapper.insertProperty(property);
                    insertRecord(order, "货币基金买入", property, amount);
                } else {
                    propertyMysql.setAmount(propertyMysql.getAmount() + amount);
                    propertyMysql.setUpdatetime((int) (System.currentTimeMillis() / 1000));
                    propertyMapper.updatePropertyAmount(propertyMysql.getPropertyid(), propertyMysql.getAmount(), propertyMysql.getUpdatetime());
                    propertyMysql.setAmount(amount);
                    insertRecord(order, "货币基金买入", propertyMysql, amount);
                }
            }


            orderMapper.updateOrderStatus(order.getId(), FINISH);
        }

        return ResultCode.SUCCESS;
    }


    /**
     * 卖出基金股票
     *
     * @param ids 订单号
     * @return 状态码
     */
    @Override
    public ResultCode sellStockAndFund(List<Integer> ids) {

        //查出所有订单详情。
        List<Order> orders = orderMapper.selectByIds(ids);
        for (Order order : orders) {
            //逐一处理每个订单，查找出订单对应的持仓信息
            List<ReturnDetailDTO> returnDetailDTOS = searchUserReturnDetail(order.getUserid());
            for (ReturnDetailDTO returnDetailDTO : returnDetailDTOS) {
                //处理每一个持仓记录
                //从redis获取产品信息
                HashMap product = getProductCache(order.getProductid());
                if (product == null) {
                    product = initProductCache(order.getProductid());
                }
                String productName = product.get("productName").toString();
                //如果持仓信息产品名是卖出的产品，则继续处理
                System.out.println(productName + " and " + returnDetailDTO.getProductName());
                if (productName.equals(returnDetailDTO.getProductName())) {
                    //卖出比例
                    String percent = order.getPercent();
                    //卖出的份额，取整
                    int sellAmount = returnDetailDTO.getAmount() * Integer.parseInt(percent) / 100;
                    //新建资产记录
                    Property property = propertyMapper.loadPropertyByCode(order.getAccountno(), returnDetailDTO.getCode());
                    property.setUserid(order.getUserid());
                    property.setOrderid(order.getOrderid());
                    property.setSignaccountid(order.getAccountno());
                    property.setType(returnDetailDTO.getType());
                    property.setCode(returnDetailDTO.getCode());
                    property.setPropertyname(returnDetailDTO.getPropertyName());
                    property.setAmount(property.getAmount() - sellAmount);
                    property.setUpdatetime((int) (System.currentTimeMillis() / 1000));
                    propertyMapper.updatePropertyAmount(property.getPropertyid(), property.getAmount(), property.getUpdatetime());
                    property.setAmount(sellAmount);

                    //更新订单状态
                    orderMapper.updateOrderStatus(order.getId(), FINISH_SELL);
                    double sum = returnDetailDTO.getAmountNow() / returnDetailDTO.getAmount() * sellAmount;
                    insertRecord(order, property.getType() + "卖出", property, (float) sum);
                    //更新用户资产
                    topUpUser(order.getUserid(), sum);

                }

            }
        }


        return ResultCode.SUCCESS;
    }

    /**
     * 临时方案，解决无法注入userService
     * @param userid
     * @param topUpMoney
     * @return
     */
    public int topUpUser(Integer userid, double topUpMoney) {
        IndependentAccount independentAccount=userMapper.selectIndependetAccountById(userid);
        double oldIndependentBalance=independentAccount.getIndependentbalance();
        double newIndependentBalance=topUpMoney+oldIndependentBalance;
        return userMapper.updateIndependentBalance(independentAccount.getUserid(),newIndependentBalance);
    }

    public List<ReturnDetailDTO> searchUserReturnDetail(Integer userId) {
        List<ReturnDetailDTO> list = new ArrayList<>(16);

        List<String> orderIdList = new ArrayList<>(16);

        List<Property> propertyList = userMapper.selectPropertyByUserId(userId);
        //map用来存储股票或基金代码以及对应的份额
        HashMap<String, Integer> map = new HashMap<String, Integer>(16);
        //Set用来对订单号去重
        HashSet<String> set = new HashSet<>(16);
        //

        for(Property p:propertyList){
            map.put(p.getCode(),p.getAmount());
            set.add(p.getOrderid());
        }

        for (String s:set){
            String orderId = s;
            Order order = userMapper.selectOrderByOrderId(orderId);
            double sum = order.getSum();
            Integer productId = order.getProductid();

            Product product = productMapper.selectProductById(productId);

            Integer stockAmount = product.getStockamount();
            Integer fundAmount = product.getFundamount();

            //查询出组合产品中 股票所占的份额
            List<ProductStockDetail> productStockDetailList = productMapper.selectStockProportionFromDetail(productId);
            for(ProductStockDetail psd:productStockDetailList){
                ReturnDetailDTO dto = new ReturnDetailDTO();
                dto.setProductName(product.getName());
                dto.setType("股票");
                dto.setCode(psd.getStockCode());
                dto.setPropertyName(psd.getStockName());
                //持有份额
                dto.setAmount(map.get(dto.getCode()));

                //单一产品买入金额 = 下单金额 * 股票比例 * 单个股票的比例
                double buyInAmount = sum * stockAmount*0.01 * psd.getProportion().doubleValue();
                //计算单一产品当前持仓
                StockAlternate stockValueNow = userMapper.selectValueNowByStockCode(psd.getStockCode());
                //持有份额 * 当前市值 = 当前持仓金额
                double d = dto.getAmount() * stockValueNow.getValueNow().doubleValue();


                //单一产品当前持仓金额
                dto.setAmountNow(d);

                //计算收益（可能为负）
                dto.setAmountOfReturnOne(d - buyInAmount);

                //计算收益率
                dto.setRateOfReturn(dto.getAmountOfReturnOne()/buyInAmount);

                list.add(dto);
            }

            //查询出组合产品中 基金所占的份额
            List<ProductFundDetail> ProductFundDetailList = productMapper.selectFundProportionFromDetail(productId);
            for(ProductFundDetail pfd:ProductFundDetailList){
                ReturnDetailDTO dto = new ReturnDetailDTO();
                dto.setProductName(product.getName());
                dto.setType("基金");
                dto.setCode(pfd.getFundCode());
                dto.setPropertyName(pfd.getFundName());
                //持有份额
                dto.setAmount(map.get(pfd.getFundCode()));

                //单一产品买入金额 = 下单金额 * 股票比例 * 单个股票的比例
                double buyInAmount = sum * fundAmount*0.01 * pfd.getProportion().doubleValue();

                //计算单一产品当前持仓
                FundAlternate fundValueNow = userMapper.selectValueNowByFundCode(pfd.getFundCode());

                //持有份额 * 当前市值 = 当前持仓金额
                double d = dto.getAmount() * fundValueNow.getValueNow().doubleValue();
                //单一产品当前持仓金额
                dto.setAmountNow(d);

                //计算收益（可能为负）
                dto.setAmountOfReturnOne(d - buyInAmount);

                //计算收益率
                dto.setRateOfReturn(dto.getAmountOfReturnOne()/buyInAmount);

                list.add(dto);
            }

        }





        return list;
    }

    /**
     * 查询交易记录
     *
     * @param pageNum      页码
     * @param pageSize     大小
     * @param orderId      订单号
     * @param consultantId 投顾人id
     * @return 交易记录列表
     */
    @Override
    public Map selectRecord(int pageNum, int pageSize, String orderId, int consultantId, int status) {
        Map<String, List<ConsultantRecord>> map = new HashMap<>(16);
        if ("0".equals(orderId)) {
            List<Order> orders = orderMapper.selectOrderByConsultantIdAndStatus(consultantId, status);

            if (orders.size() > 0) {

                for (Order order : orders) {
                    List<ConsultantRecord> records = consultantMapper.loadRecord(order.getOrderid());
                    map.put(order.getOrderid(), records);
                }
            }

        } else {
            List<ConsultantRecord> records = consultantMapper.loadRecord(orderId);
        }
        return map;
    }


    /**
     * 查询风险调仓列表
     *
     * @param consultantId 投资顾问id
     * @return 风险调仓列表
     */
    @Override
    public Map selectRiskList(int consultantId) {
        Map<String, Object> result = new HashMap<>(4);
        List<StockAlternate> resultList = new ArrayList<>();
        //查询投顾人所属产品列表
        List<Product> selectProductList = productMapper.selectProductByConsultantId(consultantId);
        for (Product product : selectProductList) {
            resultList = new ArrayList<>();
            List<ProductStockDetail> stockList = productMapper.selectStockProportionFromDetail(product.getProductid());
            List<String> codes = new ArrayList<>();
            for (ProductStockDetail detail : stockList) {
                codes.add(detail.getStockCode());
            }
            List<StockAlternate> alternates = productMapper.selectStockAlternate(codes);
            for (StockAlternate alternate : alternates) {
                if (alternate.getUpAndDown().intValue() < ADJUST_THRESHOLD) {
                    resultList.add(alternate);
                }
            }
            result.put(product.getName(),resultList);
            result.put(product.getProductid().toString(),product.getName());
        }
        result.put("adjustThreshold", ADJUST_THRESHOLD);
        return result;
    }

    private void insertRecord(Order order, String type, Property property, float sum) {
        ConsultantRecord consultantRecord = new ConsultantRecord();

        consultantRecord.setOrderid(order.getOrderid());
        consultantRecord.setSignaccountid(order.getAccountno());
        String redisKey = RedisKeyUtil.getProductKey(order.getProductid());
        HashMap product = (HashMap) redisTemplate.opsForValue().get(redisKey);
        if (product == null) {
            product = initProductCache(order.getProductid());
        }
        consultantRecord.setProductname(product.get("productName").toString());
        consultantRecord.setType(type);
        consultantRecord.setSum(sum);
        consultantRecord.setSubmittime(order.getTransactiondate());
        consultantRecord.setUpdatedtime((int) (System.currentTimeMillis() / 1000));
        if (property != null) {
            consultantRecord.setCode(property.getCode());
            consultantRecord.setPropertyname(property.getPropertyname());
            consultantRecord.setAmount(property.getAmount());
        }
        consultantMapper.insertRecord(consultantRecord);
    }

    @Override
    public ResultCode changeRisk(int productId, String oldCode, String newCode) {
        //卖出所有人的该股票，金额购买新股票，剩余钱购买货币基金
        List<String> codes=new ArrayList<>();
        codes.add(oldCode);
        codes.add(newCode);
        List<StockAlternate> stockAlternates=productMapper.selectStockAlternate(codes);
        StockAlternate oldStock;
        StockAlternate newStock;
        if (stockAlternates.get(0).getCode().equals(oldCode)){
            oldStock=userMapper.selectValueNowByStockCode(stockAlternates.get(0).getCode());
            newStock=userMapper.selectValueNowByStockCode(stockAlternates.get(1).getCode());
        }else {
            oldStock=userMapper.selectValueNowByStockCode(stockAlternates.get(1).getCode());
            newStock=userMapper.selectValueNowByStockCode(stockAlternates.get(0).getCode());
        }


        List<SignAccount> signAccounts=consultantMapper.selectSignAccountByProductId(productId);
        for (SignAccount account:signAccounts){
            Property property=propertyMapper.loadPropertyByCode(account.getSignaccountid(),oldCode);
            Order order=orderMapper.selectByOrderId(property.getOrderid());
            //卖出金额
            float sum=property.getAmount()*oldStock.getValueNow().floatValue()*STOCK_AMOUNT_LIMIT;
            topUpUser(account.getUserid(),sum);
            propertyMapper.updatePropertyAmount(property.getPropertyid(),0,(int) (System.currentTimeMillis()/1000));
            int amount = Math.round(sum / newStock.getValueNow().floatValue());
            //剩余金额
            sum=sum-amount*newStock.getValueNow().floatValue();
            Property propertyBuy=new Property();
            propertyBuy.setSignaccountid(account.getSignaccountid());
            propertyBuy.setOrderid(property.getOrderid());
            propertyBuy.setUserid(property.getUserid());
            propertyBuy.setType("股票");
            propertyBuy.setCode(newCode);
            propertyBuy.setPropertyname(newStock.getName());
            propertyBuy.setAmount(amount);
            propertyBuy.setUpdatetime((int) (System.currentTimeMillis() / 1000));
            propertyBuy.setStatus(1);
            propertyMapper.insertProperty(propertyBuy);
            insertRecord(order, "股票买入", propertyBuy, amount * newStock.getValueNow().floatValue() / STOCK_AMOUNT_LIMIT);

            //剩余金额购买货币基金
            if (sum > 0) {
                FundAlternate fundAlternate=userMapper.selectValueNowByFundCode(MONEY_FUND_CODE);
                amount = (int) Math.round(sum /fundAlternate.getValueNow().floatValue());
                Property propertyMysql = propertyMapper.loadPropertyByCode(order.getAccountno(), MONEY_FUND_CODE);
                if (propertyMysql == null) {
                    property = new Property();
                    property.setSignaccountid(order.getAccountno());
                    property.setOrderid(order.getOrderid());
                    property.setUserid(order.getUserid());
                    property.setType("货币市场型");
                    property.setCode(MONEY_FUND_CODE);
                    property.setPropertyname(MONEY_FUND_NAME);
                    property.setAmount(amount);
                    property.setUpdatetime((int) (System.currentTimeMillis() / 1000));
                    property.setStatus(0);
                    propertyMapper.insertProperty(property);
                    insertRecord(order, "货币基金买入", property, amount);
                } else {
                    propertyMysql.setAmount(propertyMysql.getAmount() + amount);
                    propertyMysql.setUpdatetime((int) (System.currentTimeMillis() / 1000));
                    propertyMapper.updatePropertyAmount(propertyMysql.getPropertyid(), propertyMysql.getAmount(), propertyMysql.getUpdatetime());
                    propertyMysql.setAmount(amount);
                    insertRecord(order, "货币基金买入", propertyMysql, amount);
                }
            }

        }
        //修改产品明细
        productMapper.updateProductStockDetail(productId,oldCode,newCode,newStock.getName());

        return ResultCode.SUCCESS;
    }


    @Override
    public Map selectCounts(int consultantId) {
        //待审批，待买入，待卖出
        HashMap<String,Integer> result=new HashMap<>(16);
        int approvalBuy=consultantMapper.selectCountsByStatus(consultantId,APPROVAL_BUY);
        int approvalSell=consultantMapper.selectCountsByStatus(consultantId,APPROVAL_SELL);
        result.put("待审批",approvalBuy+approvalSell);
        int buy=consultantMapper.selectCountsByStatus(consultantId,WAIT_TO_BUY);
        result.put("待买入",buy);
        int sell=consultantMapper.selectCountsByStatus(consultantId,WAIT_TO_SELL);
        result.put("待卖出",sell);
        //查询历史记录
        Calendar calendar=Calendar.getInstance();
        List<Product> products=productMapper.selectProductByConsultantId(consultantId);
        for (Product product:products){
            calendar.setTime(new Date());
            calendar.add(Calendar.DATE,1);
            for (int i=0;i<10;i++){
                calendar.add(Calendar.DATE,-1);
                String date=new SimpleDateFormat("yyyy-MM-dd").format(calendar.getTime());
                String redisKey=RedisKeyUtil.getProductKey(product.getProductid(),date);
                HashMap productRedis=(HashMap) redisTemplate.opsForValue().get(redisKey);
                if (productRedis==null){
                    continue;
                }
                String peopleCount=productRedis.get("peopleCount").toString();
                Integer dateResult=result.get(date);
                if (dateResult==null){
                    result.put(date,Integer.parseInt(peopleCount));
                }else {
                    result.put(date,Integer.parseInt(peopleCount)+dateResult);
                }


            }
        }


        return result;
    }
}
