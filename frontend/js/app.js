(function () {
  const TOKEN_KEY = 'fuyouxuan-token';
  const http = axios.create({ baseURL: '/api', timeout: 5000 });

  function storedToken() { return localStorage.getItem(TOKEN_KEY) || ''; }

  http.interceptors.request.use(function (config) {
    const token = storedToken();
    if (token) config.headers.authorization = token;
    return config;
  });

  http.interceptors.response.use(function (response) {
    const payload = response.data;
    if (!payload || payload.success !== true) {
      return Promise.reject(new Error((payload && payload.errorMsg) || '请求失败'));
    }
    return payload.data;
  }, function (error) {
    const status = (error.response && error.response.status) || (error.request && error.request.status);
    if (status === 401) {
      localStorage.removeItem(TOKEN_KEY);
      window.dispatchEvent(new Event('fuyouxuan-unauthorized'));
      return Promise.reject(new Error('登录已过期，请重新登录'));
    }
    if (status === 429) {
      const message = error.response && error.response.data && error.response.data.errorMsg;
      return Promise.reject(new Error(message || '操作太频繁，请稍后再试'));
    }
    return Promise.reject(new Error(error.message === 'Network Error' ? '服务暂时不可用，请检查后端是否启动' : error.message));
  });

  new Vue({
    el: '#app',
    data: function () {
      return {
        authenticated: !!storedToken(),
        user: {},
        view: 'benefits',
        pageLoading: false,
        sendingCode: false,
        loggingIn: false,
        publishing: false,
        countdown: 0,
        timer: null,
        demoCode: '',
        searchText: '',
        loginForm: { phone: '13838411438', code: '' },
        navItems: [
          { key: 'benefits', label: '福利中心', shortLabel: '福利', icon: 'el-icon-present' },
          { key: 'community', label: '员工广场', shortLabel: '广场', icon: 'el-icon-chat-dot-square' },
          { key: 'following', label: '关注动态', shortLabel: '关注', icon: 'el-icon-connection' },
          { key: 'orders', label: '我的福利', shortLabel: '订单', icon: 'el-icon-tickets' },
          { key: 'profile', label: '个人中心', shortLabel: '我的', icon: 'el-icon-user' }
        ],
        types: [],
        selectedType: null,
        providers: [],
        selectedProvider: null,
        providerDialog: false,
        vouchers: [],
        posts: [],
        myPosts: [],
        orders: [],
        paymentDialog: false,
        paymentOrder: null,
        paymentSeconds: 0,
        paymentTimer: null,
        paying: false,
        cancelling: false,
        showComposer: false,
        composer: { title: '', content: '' },
        composerFiles: [],
        categoryNames: ['节日关怀', '餐饮补贴', '健康生活', '学习成长', '通勤出行', '家庭关爱', '团队活动', '数码优选', '文化娱乐', '弹性福利'],
        benefitCovers: [
          './assets/benefits/festival.svg', './assets/benefits/meal.svg',
          './assets/benefits/health.svg', './assets/benefits/learning.svg'
        ]
      };
    },
    computed: {
      activeNavLabel: function () {
        const current = this.navItems.find(item => item.key === this.view);
        return current ? current.label : '福利中心';
      },
      pageTitle: function () {
        const titles = { benefits: '员工福利中心', community: '员工广场', following: '关注动态', orders: '我的福利', profile: '个人中心' };
        return titles[this.view] || '福优选';
      },
      uploadHeaders: function () {
        return { authorization: storedToken() };
      },
      paymentCountdownText: function () {
        const seconds = Math.max(0, this.paymentSeconds || 0);
        const minutes = Math.floor(seconds / 60);
        return String(minutes).padStart(2, '0') + ':' + String(seconds % 60).padStart(2, '0');
      },
      avatarText: function () {
        return (this.user.nickName || '福').slice(0, 1);
      },
      filteredProviders: function () {
        const keyword = this.searchText.toLowerCase();
        if (!keyword) return this.providers;
        return this.providers.filter(item => ((item.name || '') + (item.address || '')).toLowerCase().includes(keyword));
      },
      filteredPosts: function () {
        const keyword = this.searchText.toLowerCase();
        if (!keyword) return this.posts;
        return this.posts.filter(item => ((item.title || '') + (item.content || '') + (item.name || '')).toLowerCase().includes(keyword));
      }
    },
    created: function () {
      const vm = this;
      window.addEventListener('fuyouxuan-unauthorized', function () {
        vm.authenticated = false;
        vm.user = {};
      });
      window.addEventListener('hashchange', this.syncRoute);
      this.syncRoute();
      if (this.authenticated) this.bootstrap();
    },
    beforeDestroy: function () {
      window.removeEventListener('hashchange', this.syncRoute);
      if (this.timer) clearInterval(this.timer);
      this.stopPaymentTimer();
    },
    methods: {
      notifyError: function (error) {
        this.$message.error((error && error.message) || String(error));
      },
      syncRoute: function () {
        const key = location.hash.replace('#/', '') || 'benefits';
        if (this.navItems.some(item => item.key === key)) this.view = key;
        if (this.authenticated) this.loadCurrentView();
      },
      navigate: function (key) {
        if (location.hash === '#/' + key) {
          this.view = key;
          this.loadCurrentView();
        } else {
          location.hash = '#/' + key;
        }
      },
      bootstrap: async function () {
        this.pageLoading = true;
        try {
          this.user = await http.get('/user/me') || {};
          await Promise.all([this.loadTypes(), this.loadHotPosts()]);
          await this.loadCurrentView();
        } catch (error) {
          this.notifyError(error);
        } finally {
          this.pageLoading = false;
        }
      },
      loadCurrentView: async function () {
        if (!this.authenticated) return;
        if (this.view === 'benefits') return this.loadBenefits();
        if (this.view === 'community') return this.loadHotPosts();
        if (this.view === 'following') return this.loadFollowing();
        if (this.view === 'orders') return this.loadOrders();
        if (this.view === 'profile') return this.loadMyPosts();
      },
      refreshCurrent: async function () {
        this.pageLoading = true;
        try { await this.loadCurrentView(); this.$message.success('数据已刷新'); }
        catch (error) { this.notifyError(error); }
        finally { this.pageLoading = false; }
      },
      sendCode: async function () {
        if (!/^1\d{10}$/.test(this.loginForm.phone)) return this.$message.warning('请输入正确的手机号');
        this.sendingCode = true;
        try {
          const code = await http.post('/user/code', null, { params: { phone: this.loginForm.phone } });
          this.demoCode = code || '';
          if (code) this.loginForm.code = code;
          this.countdown = 60;
          const vm = this;
          this.timer = setInterval(function () {
            vm.countdown -= 1;
            if (vm.countdown <= 0) { clearInterval(vm.timer); vm.timer = null; }
          }, 1000);
          this.$message.success(code ? 'Demo验证码已自动填入' : '验证码已发送');
        } catch (error) { this.notifyError(error); }
        finally { this.sendingCode = false; }
      },
      login: async function () {
        if (!this.loginForm.phone || !this.loginForm.code) return this.$message.warning('请填写手机号和验证码');
        this.loggingIn = true;
        try {
          const token = await http.post('/user/login', this.loginForm);
          localStorage.setItem(TOKEN_KEY, token);
          this.authenticated = true;
          await this.bootstrap();
          this.$message.success('欢迎进入福优选');
        } catch (error) { this.notifyError(error); }
        finally { this.loggingIn = false; }
      },
      logout: async function () {
        try { await http.post('/user/logout'); } catch (ignored) {}
        localStorage.removeItem(TOKEN_KEY);
        this.authenticated = false;
        this.user = {};
        this.demoCode = '';
        location.hash = '';
      },
      loadTypes: async function () {
        const data = await http.get('/shop-type/list');
        this.types = (data || []).map((item, index) => Object.assign({}, item, { name: this.categoryNames[index] || item.name }));
        if (!this.selectedType && this.types.length) this.selectedType = this.types[0].id;
      },
      loadBenefits: async function () {
        if (!this.types.length) await this.loadTypes();
        if (!this.selectedType) return;
        this.providers = await http.get('/shop/of/type', { params: { typeId: this.selectedType, current: 1 } }) || [];
      },
      selectType: async function (id) {
        this.selectedType = id;
        this.pageLoading = true;
        try { await this.loadBenefits(); } catch (error) { this.notifyError(error); }
        finally { this.pageLoading = false; }
      },
      openProvider: async function (provider) {
        this.pageLoading = true;
        try {
          const results = await Promise.all([http.get('/shop/' + provider.id), http.get('/voucher/list/' + provider.id)]);
          this.selectedProvider = results[0] || provider;
          this.vouchers = results[1] || [];
          this.providerDialog = true;
        } catch (error) { this.notifyError(error); }
        finally { this.pageLoading = false; }
      },
      seckill: async function (voucher) {
        try {
          const orderId = await http.post('/voucher-order/seckill/' + voucher.id);
          this.$message.success('抢购资格已受理，订单号：' + orderId);
          this.providerDialog = false;
          await this.pollOrder(orderId, 0);
        } catch (error) { this.notifyError(error); }
      },
      pollOrder: async function (orderId, attempt) {
        if (attempt > 12) return this.$message.info('订单正在异步处理，可稍后在“我的福利”查看');
        const vm = this;
        setTimeout(async function () {
          try {
            const result = await http.get('/voucher-order/' + orderId + '/status');
            if (result.status === 'UNPAID') {
              vm.$message.success('抢购成功，请在 5 分钟内完成付款');
              await vm.loadOrders();
              vm.openPayment(result);
            } else if (result.status === 'PAID') {
              vm.$message.success('订单已经付款');
            } else if (result.status === 'FAILED') {
              vm.$message.error('订单处理失败，库存资格已自动恢复');
            } else {
              vm.pollOrder(orderId, attempt + 1);
            }
          } catch (error) { vm.notifyError(error); }
        }, 700);
      },
      loadOrders: async function () {
        this.orders = await http.get('/voucher-order/of/me') || [];
      },
      openOrderPayment: function (order) {
        this.openPayment(order);
      },
      openPayment: function (order) {
        this.paymentOrder = Object.assign({}, order);
        this.paymentDialog = true;
        this.stopPaymentTimer();
        const expiresAt = order.expiresAt ? new Date(order.expiresAt).getTime() : Date.now() + 5 * 60 * 1000;
        const update = () => {
          this.paymentSeconds = Math.max(0, Math.ceil((expiresAt - Date.now()) / 1000));
          if (this.paymentSeconds <= 0) {
            this.stopPaymentTimer();
            if (this.paymentOrder) this.waitForPaymentClose(this.paymentOrder.orderId, 0);
          }
        };
        update();
        if (this.paymentSeconds > 0) this.paymentTimer = setInterval(update, 1000);
      },
      stopPaymentTimer: function () {
        if (this.paymentTimer) clearInterval(this.paymentTimer);
        this.paymentTimer = null;
      },
      waitForPaymentClose: async function (orderId, attempt) {
        if (!this.paymentDialog || !this.paymentOrder || this.paymentOrder.orderId !== orderId) return;
        try {
          const result = await http.get('/voucher-order/' + orderId + '/status');
          if (result.status === 'CANCELLED' || result.status === 'FAILED' || result.status === 'UNKNOWN') {
            this.paymentDialog = false;
            await this.loadOrders();
            this.$message.info('订单已超时取消，库存和抢购资格已恢复');
            return;
          }
          if (result.status === 'PAID') {
            this.paymentDialog = false;
            const refreshed = await Promise.all([this.loadOrders(), http.get('/user/me')]);
            this.user = refreshed[1] || this.user;
            return;
          }
        } catch (error) {
          if (attempt >= 10) this.notifyError(error);
        }
        if (attempt < 10) {
          setTimeout(() => this.waitForPaymentClose(orderId, attempt + 1), 1000);
        }
      },
      payCurrentOrder: async function () {
        if (!this.paymentOrder || this.paymentSeconds <= 0) return;
        this.paying = true;
        try {
          const result = await http.post('/voucher-order/' + this.paymentOrder.orderId + '/pay');
          this.user.credits = result.credits;
          this.paymentDialog = false;
          this.$message.success(result.alreadyPaid ? '订单已经支付，无需重复扣豆' : '支付成功');
          await this.loadOrders();
        } catch (error) { this.notifyError(error); }
        finally { this.paying = false; }
      },
      cancelPaymentOrder: async function () {
        if (!this.paymentOrder) return;
        this.cancelling = true;
        try {
          await http.post('/voucher-order/' + this.paymentOrder.orderId + '/cancel');
          this.paymentDialog = false;
          this.$message.success('订单已取消，库存和抢购资格已恢复');
          await this.loadOrders();
        } catch (error) { this.notifyError(error); }
        finally { this.cancelling = false; }
      },
      checkOrder: async function (orderId) {
        try {
          const result = await http.get('/voucher-order/' + orderId + '/status');
          const labels = { UNPAID: '订单待付款', PAID: '订单已付款', PENDING: '订单正在异步处理', FAILED: '订单处理失败，资格已恢复', CANCELLED: '订单已取消', UNKNOWN: '暂未找到订单状态' };
          this.$message.info(labels[result.status] || result.status);
        } catch (error) { this.notifyError(error); }
      },
      loadHotPosts: async function () {
        this.posts = await http.get('/blog/hot', { params: { current: 1 } }) || [];
      },
      loadFollowing: async function () {
        const data = await http.get('/blog/of/follow', { params: { lastId: Date.now(), offset: 0 } });
        this.posts = data && data.list ? data.list : [];
      },
      loadMyPosts: async function () {
        this.myPosts = await http.get('/blog/of/me', { params: { current: 1 } }) || [];
      },
      toggleLike: async function (post) {
        try {
          await http.put('/blog/like/' + post.id);
          post.isLike = !post.isLike;
          post.liked = Math.max(0, (post.liked || 0) + (post.isLike ? 1 : -1));
        } catch (error) { this.notifyError(error); }
      },
      toggleFollow: async function (employeeId) {
        try {
          const followed = await http.get('/follow/or/not/' + employeeId);
          await http.put('/follow/' + employeeId + '/' + (!followed));
          this.$message.success(followed ? '已取消关注' : '关注成功，新动态会进入关注流');
        } catch (error) { this.notifyError(error); }
      },
      deletePost: async function (post) {
        try {
          await this.$confirm('删除后无法恢复，确定删除这条动态吗？', '删除动态', {
            confirmButtonText: '确定删除',
            cancelButtonText: '取消',
            type: 'warning'
          });
        } catch (ignored) {
          return;
        }
        try {
          await http.delete('/blog/' + post.id);
          this.posts = this.posts.filter(item => item.id !== post.id);
          this.myPosts = this.myPosts.filter(item => item.id !== post.id);
          this.$message.success('动态已删除');
        } catch (error) {
          this.notifyError(error);
        }
      },
      beforeImageUpload: function (file) {
        const allowedTypes = ['image/jpeg', 'image/png', 'image/webp', 'image/gif'];
        if (!allowedTypes.includes(file.type)) {
          this.$message.warning('只能上传 JPG、PNG、WebP 或 GIF 图片');
          return false;
        }
        if (file.size > 5 * 1024 * 1024) {
          this.$message.warning('单张图片不能超过 5 MB');
          return false;
        }
        return true;
      },
      handleImageUploadSuccess: function (response, file, fileList) {
        if (!response || response.success !== true || !response.data) {
          this.$message.error((response && response.errorMsg) || '图片上传失败');
          this.composerFiles = fileList.filter(item => item.uid !== file.uid);
          return;
        }
        file.serverPath = response.data;
        file.url = '/assets/uploads' + response.data;
        this.composerFiles = fileList.slice();
      },
      handleImageUploadError: function (error, file, fileList) {
        this.composerFiles = fileList.filter(item => item.uid !== file.uid);
        this.$message.error('图片上传失败，请稍后重试');
      },
      handleImageRemove: async function (file, fileList) {
        this.composerFiles = fileList.slice();
        const serverPath = file.serverPath || (file.response && file.response.data);
        if (!serverPath) return;
        try {
          await http.get('/upload/blog/delete', { params: { name: serverPath } });
        } catch (error) {
          this.notifyError(error);
        }
      },
      handleImageExceed: function () {
        this.$message.warning('一条动态最多上传 9 张图片');
      },
      publishPost: async function () {
        if (!this.composer.title || !this.composer.content) return this.$message.warning('请填写标题和内容');
        if (this.composerFiles.some(file => file.status === 'ready' || file.status === 'uploading')) {
          return this.$message.warning('图片还在上传，请稍等');
        }
        const images = this.composerFiles.map(function (file) {
          const serverPath = file.serverPath || (file.response && file.response.data);
          return serverPath ? '/assets/uploads' + serverPath : '';
        }).filter(Boolean);
        this.publishing = true;
        try {
          await http.post('/blog', {
            shopId: 1,
            title: this.composer.title,
            content: this.composer.content,
            images: images.join(',') || '/assets/benefits/community.svg'
          });
          this.showComposer = false;
          this.composer = { title: '', content: '' };
          this.composerFiles = [];
          if (this.$refs.composerUpload) this.$refs.composerUpload.clearFiles();
          this.$message.success('动态已发布，并推送给关注你的同事');
          this.navigate('community');
          await this.loadHotPosts();
        } catch (error) { this.notifyError(error); }
        finally { this.publishing = false; }
      },
      copyPostLink: function (post) {
        const text = '福优选员工动态：' + post.title;
        if (navigator.clipboard) navigator.clipboard.writeText(text);
        this.$message.success('动态标题已复制');
      },
      scrollToBenefits: function () {
        if (this.$refs.benefitSection) this.$refs.benefitSection.scrollIntoView({ behavior: 'smooth', block: 'start' });
      },
      categoryEmoji: function (index) { return ['🎁','🍱','💪','📚','🚇','🏠','🎉','💻','🎬','✨'][index] || '✨'; },
      coverStyle: function (provider, index) {
        return { backgroundImage: 'url("' + this.benefitCovers[Math.abs(Number(index) || 0) % this.benefitCovers.length] + '")' };
      },
      firstImage: function (images) { return (images || '').split(',')[0] || './assets/benefits/community.svg'; },
      imageFallback: function (event) { event.target.src = './assets/benefits/community.svg'; },
      creditPrice: function (cents) { return Math.ceil((Number(cents) || 0) / 100); },
      formatMoney: function (cents) { return ((Number(cents) || 0) / 100).toFixed(2); },
      formatDate: function (value) {
        if (!value) return '刚刚';
        const date = new Date(value);
        if (Number.isNaN(date.getTime())) return String(value);
        return date.toLocaleString('zh-CN', { month:'2-digit', day:'2-digit', hour:'2-digit', minute:'2-digit' });
      },
      formatActivity: function (voucher) {
        if (!voucher.beginTime || !voucher.endTime) return '长期有效';
        return this.formatDate(voucher.beginTime) + ' 开始';
      },
      canSeckill: function (voucher) {
        const now = Date.now();
        return voucher.type === 1 && voucher.stock > 0 && (!voucher.beginTime || new Date(voucher.beginTime).getTime() <= now) && (!voucher.endTime || new Date(voucher.endTime).getTime() >= now);
      }
    }
  });
})();
