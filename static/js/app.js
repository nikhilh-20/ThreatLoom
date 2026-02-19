// === State ===
let currentPage = 1;
let currentSource = '';
let currentSearch = '';
let currentTag = '';
let isLoading = false;
let hasMore = true;
let cachedCategories = null; // cache so drill-down doesn't refetch
let currentDrilldownCategory = null;
let currentSubcategory = null;       // tag string, e.g. "apt29"
let currentSubcategoryName = null;   // display name, e.g. "APT29"
let cachedSubcategories = null;      // cache for current category's subcategories
let viewingAllArticles = false;      // true when showing "View All" for a subcategorizable category
let currentDays = 0;                 // 0 = All; positive integer = days filter
const SUBCATEGORIZABLE = new Set(["Threat Actors", "Malware", "C2 & Offensive Tooling"]);

// === Debounce utility ===
function debounce(fn, ms) {
    let timer;
    return function (...args) {
        clearTimeout(timer);
        timer = setTimeout(() => fn.apply(this, args), ms);
    };
}

// === Stats ===
async function loadStats() {
    try {
        const res = await fetch('/api/stats');
        const data = await res.json();
        const el = (id) => document.getElementById(id);
        if (el('stat-articles')) el('stat-articles').textContent = data.total_articles;
        if (el('stat-sources')) el('stat-sources').textContent = data.total_sources;
        if (el('stat-summaries')) el('stat-summaries').textContent = data.total_summaries;
        if (el('stat-unsummarized')) el('stat-unsummarized').textContent = data.unsummarized ?? '--';
        if (el('stat-scrape-failed')) el('stat-scrape-failed').textContent = data.scrape_failed ?? '--';
    } catch (e) {
        console.error('Failed to load stats:', e);
    }
}

// === Sources ===
async function loadSources() {
    try {
        const res = await fetch('/api/sources');
        const sources = await res.json();
        const select = document.getElementById('source-filter');
        if (!select) return;
        sources.forEach(s => {
            const opt = document.createElement('option');
            opt.value = s.id;
            opt.textContent = s.name;
            select.appendChild(opt);
        });
    } catch (e) {
        console.error('Failed to load sources:', e);
    }
}

// === Time Filter ===
function setTimeFilter(days) {
    currentDays = days;
    // Update pill active state
    document.querySelectorAll('.time-filter-pill').forEach(pill => {
        pill.classList.remove('active');
    });
    const pills = document.querySelectorAll('.time-filter-pill');
    const labels = [0, 1, 7, 30, 90];
    labels.forEach((d, i) => {
        if (d === days && pills[i]) pills[i].classList.add('active');
    });
    // Reload category grid with new filter
    cachedCategories = null;
    loadCategories();
}

// === View Management ===
function showView(viewId) {
    ['categories-view', 'drilldown-view', 'search-view'].forEach(id => {
        const el = document.getElementById(id);
        if (el) el.style.display = id === viewId ? 'block' : 'none';
    });
    const emptyState = document.getElementById('empty-state');
    if (emptyState) emptyState.style.display = 'none';
}

// === Categories (home page) ===
async function loadCategories() {
    const grid = document.getElementById('categories-grid');
    const skeleton = document.getElementById('categories-skeleton');
    const emptyState = document.getElementById('empty-state');

    if (!grid) return;
    if (skeleton) skeleton.style.display = 'grid';
    grid.innerHTML = '';
    showView('categories-view');

    try {
        const daysParam = currentDays > 0 ? `&days=${currentDays}` : '';
        const res = await fetch(`/api/articles/categorized?limit=50${daysParam}`);
        const categories = await res.json();
        cachedCategories = categories;

        if (skeleton) skeleton.style.display = 'none';

        if (categories.length === 0) {
            if (emptyState) {
                emptyState.style.display = 'block';
                const statsRes = await fetch('/api/stats');
                const stats = await statsRes.json();
                const warning = document.getElementById('api-key-warning');
                if (warning && stats.total_articles === 0) {
                    warning.style.display = 'block';
                }
            }
            return;
        }

        if (emptyState) emptyState.style.display = 'none';

        categories.forEach(cat => {
            grid.appendChild(createCategoryCard(cat));
        });
    } catch (e) {
        console.error('Failed to load categories:', e);
        if (skeleton) skeleton.style.display = 'none';
    }
}

function createCategoryCard(category) {
    const card = document.createElement('div');
    card.className = 'category-card';
    card.onclick = () => openCategory(category.name);

    card.innerHTML = `
        <div class="category-card-icon">${getCategoryIcon(category.name)}</div>
        <h3 class="category-card-name">${escHtml(category.name)}</h3>
        <span class="category-card-count">${category.count} article${category.count !== 1 ? 's' : ''}</span>
    `;

    return card;
}

function getCategoryIcon(name) {
    const icons = {
        'Malware': '&#129440;',
        'Vulnerabilities': '&#128027;',
        'Threat Actors': '&#127946;',
        'Data Leaks': '&#128451;',
        'Phishing & Social Engineering': '&#127907;',
        'Supply Chain': '&#128279;',
        'Botnet & DDoS': '&#127760;',
        'C2 & Offensive Tooling': '&#128296;',
        'IoT & Hardware': '&#128225;',
    };
    return icons[name] || '&#128196;';
}

// === Category Drill-down ===
function _resetInsightPanel() {
    ['insight-panel', 'insight-loading', 'insight-content', 'insight-error',
     'trend-analysis-panel', 'trend-loading', 'trend-content', 'trend-error'].forEach(id => {
        const el = document.getElementById(id);
        if (el) el.style.display = 'none';
    });
    const trendBtn = document.getElementById('insight-trend-btn');
    if (trendBtn) trendBtn.disabled = false;
    const forecastBtn = document.getElementById('insight-forecast-btn');
    if (forecastBtn) forecastBtn.disabled = false;
}

// === Historical Trend Analysis ===
function createPeriodItem(item) {
    const details = document.createElement('details');
    details.className = 'trend-period-item';
    const summary = document.createElement('summary');
    summary.className = 'trend-period-summary';
    summary.innerHTML = `<strong>${escHtml(item.period)}</strong>
        <span class="category-card-count">${item.article_count} article${item.article_count !== 1 ? 's' : ''}</span>`;
    const body = document.createElement('div');
    body.className = 'trend-period-body markdown-body';
    body.innerHTML = renderMarkdown(item.trend_text);
    details.append(summary, body);
    return details;
}

async function loadTrendAnalysis() {
    const panel = document.getElementById('trend-analysis-panel');
    const loading = document.getElementById('trend-loading');
    const content = document.getElementById('trend-content');
    const errorEl = document.getElementById('trend-error');
    const btn = document.getElementById('insight-trend-btn');

    if (!panel || !currentDrilldownCategory) return;

    // Toggle: if panel is visible with content, hide it
    if (panel.style.display !== 'none' && content && content.style.display !== 'none') {
        panel.style.display = 'none';
        return;
    }

    if (btn) btn.disabled = true;

    // Build shared query params
    const daysParam = currentDays > 0 ? `&days=${currentDays}` : '';
    const subParam = (currentSubcategory && currentSubcategory !== '__general__')
        ? '&subcategory=' + encodeURIComponent(currentSubcategory) : '';
    const baseParams = 'category=' + encodeURIComponent(currentDrilldownCategory) + subParam + daysParam;

    // Step 1: fetch cost estimate
    try {
        const estRes = await fetch('/api/insight-estimate?type=trend&' + baseParams);
        const estData = await estRes.json();
        if (estData.error === 'insufficient_data') {
            panel.style.display = 'block';
            if (errorEl) {
                errorEl.textContent = 'Not enough data \u2014 at least 3 summarized articles are needed for trend analysis.';
                errorEl.style.display = 'block';
            }
            if (btn) btn.disabled = false;
            return;
        }
        if (estData.error) {
            panel.style.display = 'block';
            if (errorEl) { errorEl.textContent = 'Could not fetch cost estimate.'; errorEl.style.display = 'block'; }
            if (btn) btn.disabled = false;
            return;
        }

        // Step 2: ask for confirmation
        const proceed = await _askInsightConfirm(estData, 'trend');
        if (!proceed) {
            if (btn) btn.disabled = false;
            return;
        }
    } catch (e) {
        // If estimate fetch fails, skip confirmation and proceed
    }

    // Step 3: show loading, run analysis
    panel.style.display = 'block';
    if (loading) loading.style.display = 'block';
    if (content) content.style.display = 'none';
    if (errorEl) errorEl.style.display = 'none';

    try {
        const res = await fetch('/api/trend-analysis?' + baseParams);
        const data = await res.json();

        if (loading) loading.style.display = 'none';

        if (data.error === 'insufficient_data') {
            if (errorEl) {
                errorEl.textContent = 'Not enough data \u2014 at least 3 summarized articles are needed for trend analysis.';
                errorEl.style.display = 'block';
            }
        } else if (data.error) {
            if (errorEl) {
                errorEl.innerHTML = 'Failed to generate trend analysis. <a href="#" onclick="loadTrendAnalysis(); return false;">Retry</a>';
                errorEl.style.display = 'block';
            }
        } else {
            const quarterlyList = document.getElementById('quarterly-list');
            const yearlyList = document.getElementById('yearly-list');
            const metaEl = document.getElementById('trend-meta');

            if (quarterlyList) {
                quarterlyList.innerHTML = '';
                (data.quarterly || []).forEach(item => quarterlyList.appendChild(createPeriodItem(item)));
            }
            if (yearlyList) {
                yearlyList.innerHTML = '';
                (data.yearly || []).forEach(item => yearlyList.appendChild(createPeriodItem(item)));
            }
            if (metaEl) {
                const daysLabel = currentDays > 0 ? ` (last ${currentDays}d)` : '';
                metaEl.textContent =
                    `Based on ${(data.quarterly || []).length} quarters \u2022 ${(data.yearly || []).length} years${daysLabel} \u2022 ${data.model_used}`;
            }
            if (content) content.style.display = 'block';

            // Step 4: show actual cost
            const totalArticles = (data.quarterly || []).reduce((s, q) => s + q.article_count, 0);
            _showInsightActualModal('trend', data.actual_cost || 0, totalArticles, data.model_used, false);
        }
    } catch (e) {
        console.error('Failed to load trend analysis:', e);
        if (loading) loading.style.display = 'none';
        if (errorEl) {
            errorEl.innerHTML = 'Network error. <a href="#" onclick="loadTrendAnalysis(); return false;">Retry</a>';
            errorEl.style.display = 'block';
        }
    }

    if (btn) btn.disabled = false;
}

function openCategory(categoryName) {
    showView('drilldown-view');
    currentDrilldownCategory = categoryName;
    currentSubcategory = null;
    currentSubcategoryName = null;
    cachedSubcategories = null;
    viewingAllArticles = false;
    _resetInsightPanel();

    const title = document.getElementById('drilldown-title');
    const breadcrumb = document.getElementById('drilldown-breadcrumb');
    const count = document.getElementById('drilldown-count');
    const grid = document.getElementById('drilldown-grid');
    const subGrid = document.getElementById('subcategories-grid');

    if (title) title.textContent = categoryName;
    if (breadcrumb) breadcrumb.style.display = 'none';
    if (grid) grid.innerHTML = '';
    if (subGrid) { subGrid.innerHTML = ''; subGrid.style.display = 'none'; }

    // Find articles from cache
    const cat = cachedCategories
        ? cachedCategories.find(c => c.name === categoryName)
        : null;

    if (!cat || cat.articles.length === 0) {
        if (grid) grid.innerHTML = '<div class="empty-state"><p>No articles in this category.</p></div>';
        if (count) count.textContent = '';
        return;
    }

    if (count) count.textContent = `${cat.count} article${cat.count !== 1 ? 's' : ''}`;

    // If subcategorizable, fetch and show sub-categories
    if (SUBCATEGORIZABLE.has(categoryName)) {
        if (grid) grid.style.display = 'none';
        _loadSubcategories(categoryName, cat);
    } else {
        if (grid) grid.style.display = '';
        cat.articles.forEach(article => {
            grid.appendChild(createArticleCard(article));
        });
    }
}

async function _loadSubcategories(categoryName, cat) {
    const subGrid = document.getElementById('subcategories-grid');
    if (!subGrid) return;

    try {
        const daysParam = currentDays > 0 ? `&days=${currentDays}` : '';
        const res = await fetch('/api/subcategories?category=' + encodeURIComponent(categoryName) + '&limit=50' + daysParam);
        const subs = await res.json();
        cachedSubcategories = subs;

        subGrid.innerHTML = '';

        // "View All" card first
        const viewAll = document.createElement('div');
        viewAll.className = 'subcategory-card subcategory-view-all';
        viewAll.onclick = () => showAllCategoryArticles();
        viewAll.innerHTML = `
            <h3 class="subcategory-card-name">View All Articles</h3>
            <span class="category-card-count">${cat.count} article${cat.count !== 1 ? 's' : ''}</span>
        `;
        subGrid.appendChild(viewAll);

        // Sub-category cards
        subs.forEach(sub => {
            subGrid.appendChild(createSubcategoryCard(sub));
        });

        subGrid.style.display = 'grid';
    } catch (e) {
        console.error('Failed to load subcategories:', e);
        // Fallback: show articles directly
        const grid = document.getElementById('drilldown-grid');
        if (grid) {
            grid.style.display = '';
            cat.articles.forEach(article => {
                grid.appendChild(createArticleCard(article));
            });
        }
    }
}

function createSubcategoryCard(sub) {
    const card = document.createElement('div');
    card.className = 'subcategory-card';
    card.onclick = () => openSubcategory(sub.tag, sub.display_name);

    card.innerHTML = `
        <h3 class="subcategory-card-name">${escHtml(sub.display_name)}</h3>
        <span class="category-card-count">${sub.count} article${sub.count !== 1 ? 's' : ''}</span>
    `;

    return card;
}

function openSubcategory(tag, displayName) {
    currentSubcategory = tag;
    currentSubcategoryName = displayName;
    viewingAllArticles = false;
    _resetInsightPanel();

    const title = document.getElementById('drilldown-title');
    const breadcrumb = document.getElementById('drilldown-breadcrumb');
    const grid = document.getElementById('drilldown-grid');
    const subGrid = document.getElementById('subcategories-grid');
    const count = document.getElementById('drilldown-count');

    if (title) title.textContent = currentDrilldownCategory;
    if (breadcrumb) {
        breadcrumb.textContent = displayName;
        breadcrumb.style.display = 'inline';
    }

    if (subGrid) subGrid.style.display = 'none';
    if (grid) {
        grid.innerHTML = '';
        grid.style.display = '';
    }

    // Find articles from cached subcategories
    const sub = cachedSubcategories
        ? cachedSubcategories.find(s => s.tag === tag)
        : null;

    if (!sub || sub.articles.length === 0) {
        if (grid) grid.innerHTML = '<div class="empty-state"><p>No articles for this entity.</p></div>';
        if (count) count.textContent = '';
        return;
    }

    if (count) count.textContent = `${sub.count} article${sub.count !== 1 ? 's' : ''}`;

    sub.articles.forEach(article => {
        grid.appendChild(createArticleCard(article));
    });
}

function showAllCategoryArticles() {
    currentSubcategory = null;
    currentSubcategoryName = null;
    viewingAllArticles = true;
    _resetInsightPanel();

    const title = document.getElementById('drilldown-title');
    const breadcrumb = document.getElementById('drilldown-breadcrumb');
    const grid = document.getElementById('drilldown-grid');
    const subGrid = document.getElementById('subcategories-grid');
    const count = document.getElementById('drilldown-count');

    if (title) title.textContent = currentDrilldownCategory;
    if (breadcrumb) breadcrumb.style.display = 'none';
    if (subGrid) subGrid.style.display = 'none';
    if (grid) {
        grid.innerHTML = '';
        grid.style.display = '';
    }

    const cat = cachedCategories
        ? cachedCategories.find(c => c.name === currentDrilldownCategory)
        : null;

    if (!cat || cat.articles.length === 0) {
        if (grid) grid.innerHTML = '<div class="empty-state"><p>No articles in this category.</p></div>';
        if (count) count.textContent = '';
        return;
    }

    if (count) count.textContent = `${cat.count} article${cat.count !== 1 ? 's' : ''}`;

    cat.articles.forEach(article => {
        grid.appendChild(createArticleCard(article));
    });
}

function drilldownBack() {
    if (currentSubcategory || viewingAllArticles) {
        // Go back from sub-category articles (or "View All") to sub-categories grid
        currentSubcategory = null;
        currentSubcategoryName = null;
        viewingAllArticles = false;
        _resetInsightPanel();

        const title = document.getElementById('drilldown-title');
        const breadcrumb = document.getElementById('drilldown-breadcrumb');
        const grid = document.getElementById('drilldown-grid');
        const subGrid = document.getElementById('subcategories-grid');

        if (title) title.textContent = currentDrilldownCategory;
        if (breadcrumb) breadcrumb.style.display = 'none';
        if (grid) { grid.innerHTML = ''; grid.style.display = 'none'; }
        if (subGrid) subGrid.style.display = 'grid';

        // Restore count for overall category
        const count = document.getElementById('drilldown-count');
        const cat = cachedCategories
            ? cachedCategories.find(c => c.name === currentDrilldownCategory)
            : null;
        if (count && cat) count.textContent = `${cat.count} article${cat.count !== 1 ? 's' : ''}`;
    } else {
        showCategories();
    }
}

function showCategories() {
    showView('categories-view');
    currentDrilldownCategory = null;
    currentSubcategory = null;
    currentSubcategoryName = null;
    cachedSubcategories = null;
    viewingAllArticles = false;
}

// === Category Insight (Trend & Forecast) ===
async function loadCategoryInsight() {
    const panel = document.getElementById('insight-panel');
    const loading = document.getElementById('insight-loading');
    const content = document.getElementById('insight-content');
    const errorEl = document.getElementById('insight-error');
    const btn = document.getElementById('insight-forecast-btn');

    if (!panel || !currentDrilldownCategory) return;

    // Toggle: if panel is visible and has content, hide it
    if (panel.style.display !== 'none' && content && content.style.display !== 'none') {
        panel.style.display = 'none';
        return;
    }

    if (btn) btn.disabled = true;

    // Build shared query params
    const daysParam = currentDays > 0 ? `&days=${currentDays}` : '';
    const subParam = (currentSubcategory && currentSubcategory !== '__general__')
        ? '&subcategory=' + encodeURIComponent(currentSubcategory) : '';
    const baseParams = 'category=' + encodeURIComponent(currentDrilldownCategory) + subParam + daysParam;

    // Step 1: fetch cost estimate
    try {
        const estRes = await fetch('/api/insight-estimate?type=forecast&' + baseParams);
        const estData = await estRes.json();
        if (estData.error === 'insufficient_data') {
            panel.style.display = 'block';
            if (errorEl) {
                errorEl.textContent = 'Not enough data \u2014 at least 3 summarized articles are needed for a forecast.';
                errorEl.style.display = 'block';
            }
            if (btn) btn.disabled = false;
            return;
        }
        if (estData.error) {
            panel.style.display = 'block';
            if (errorEl) { errorEl.textContent = 'Could not fetch cost estimate.'; errorEl.style.display = 'block'; }
            if (btn) btn.disabled = false;
            return;
        }

        // Skip confirm modal if this will be served from cache (cost = 0)
        if (estData.estimated_cost > 0) {
            const proceed = await _askInsightConfirm(estData, 'forecast');
            if (!proceed) {
                if (btn) btn.disabled = false;
                return;
            }
        }
    } catch (e) {
        // If estimate fetch fails, proceed without confirmation
    }

    // Step 2: show loading, run forecast
    panel.style.display = 'block';
    if (loading) loading.style.display = 'block';
    if (content) content.style.display = 'none';
    if (errorEl) errorEl.style.display = 'none';

    try {
        const res = await fetch('/api/category-insight?' + baseParams);
        const data = await res.json();

        if (loading) loading.style.display = 'none';

        if (data.error === 'insufficient_data') {
            if (errorEl) {
                errorEl.textContent = 'Not enough data \u2014 at least 3 summarized articles are needed for a forecast.';
                errorEl.style.display = 'block';
            }
        } else if (data.error) {
            if (errorEl) {
                errorEl.innerHTML = 'Failed to generate forecast. <a href="#" onclick="loadCategoryInsight(); return false;">Retry</a>';
                errorEl.style.display = 'block';
            }
        } else {
            // Success — render markdown
            const trendEl = document.getElementById('insight-trend');
            const forecastEl = document.getElementById('insight-forecast');
            const metaEl = document.getElementById('insight-meta');

            if (trendEl) trendEl.innerHTML = renderMarkdown(data.trend);
            if (forecastEl) forecastEl.innerHTML = renderMarkdown(data.forecast);
            if (metaEl) {
                const daysLabel = currentDays > 0 ? ` (last ${currentDays}d)` : '';
                const cacheLabel = data.cached ? 'cached' : 'fresh';
                metaEl.textContent = `Based on ${data.article_count} articles${daysLabel} \u2022 ${cacheLabel} \u2022 ${data.model_used}`;
            }
            if (content) content.style.display = 'block';

            // Step 3: show actual cost
            _showInsightActualModal('forecast', data.actual_cost || 0, data.article_count, data.model_used, data.cached);
        }
    } catch (e) {
        console.error('Failed to load category insight:', e);
        if (loading) loading.style.display = 'none';
        if (errorEl) {
            errorEl.innerHTML = 'Network error. <a href="#" onclick="loadCategoryInsight(); return false;">Retry</a>';
            errorEl.style.display = 'block';
        }
    }

    if (btn) btn.disabled = false;
}

// === Articles (search results) ===
async function loadArticles(append = false) {
    if (isLoading) return;
    isLoading = true;

    const grid = document.getElementById('articles-grid');
    const skeleton = document.getElementById('loading-skeleton');
    const emptyState = document.getElementById('empty-state');
    const loadMoreWrap = document.getElementById('load-more-wrapper');

    if (!append && skeleton) skeleton.style.display = 'grid';
    if (!append && grid) grid.innerHTML = '';

    const params = new URLSearchParams({
        page: currentPage,
        limit: 20,
    });
    if (currentSource) params.set('source_id', currentSource);
    if (currentSearch) params.set('search', currentSearch);
    if (currentTag) params.set('tag', currentTag);

    try {
        const res = await fetch('/api/articles?' + params);
        const articles = await res.json();

        if (skeleton) skeleton.style.display = 'none';

        if (articles.length === 0 && !append) {
            if (emptyState) {
                emptyState.style.display = 'block';
            }
            if (loadMoreWrap) loadMoreWrap.style.display = 'none';
            hasMore = false;
        } else {
            if (emptyState) emptyState.style.display = 'none';
            articles.forEach(a => {
                if (grid) grid.appendChild(createArticleCard(a));
            });
            hasMore = articles.length === 20;
            if (loadMoreWrap) loadMoreWrap.style.display = hasMore ? 'block' : 'none';
        }
    } catch (e) {
        console.error('Failed to load articles:', e);
        if (skeleton) skeleton.style.display = 'none';
    }

    isLoading = false;
}

function createArticleCard(article) {
    const card = document.createElement('div');
    card.className = 'article-card';
    card.onclick = () => toggleCard(card);

    let tags = [];
    try { tags = JSON.parse(article.tags || '[]'); } catch (e) {}

    let keyPoints = [];
    try { keyPoints = JSON.parse(article.key_points || '[]'); } catch (e) {}

    const date = article.published_date
        ? new Date(article.published_date).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
        : '';

    const previewText = extractExecSummary(article.summary_text) || 'Summary pending...';

    card.innerHTML = `
        <div class="article-card-header">
            <span class="source-badge">${escHtml(article.source_name)}</span>
            <span class="article-date">${escHtml(date)}</span>
        </div>
        <h3 class="article-card-title">${escHtml(article.title)}</h3>
        <p class="article-card-summary">${escHtml(previewText)}</p>
        <div class="article-card-tags">
            ${tags.map(t => `<span class="tag-pill">${escHtml(t)}</span>`).join('')}
        </div>
        <div class="article-card-expanded">
            <div class="expanded-section">
                <div class="markdown-body expanded-summary">${renderMarkdown(article.summary_text)}</div>
            </div>
            ${keyPoints.length ? `
                <div class="expanded-section">
                    ${keyPoints[0] && typeof keyPoints[0] === 'object' && keyPoints[0].phase ? `
                        <h3>Attack Sequence</h3>
                        <ol class="key-points-list attack-flow-list">
                            ${keyPoints.map(p => `<li><strong>${escHtml(p.phase)}</strong>: ${escHtml(p.title)}${p.technique ? ` <code>${escHtml(p.technique)}</code>` : ''}</li>`).join('')}
                        </ol>
                    ` : `
                        <h3>Key Points</h3>
                        <ul class="key-points-list">
                            ${keyPoints.map(p => `<li>${escHtml(typeof p === 'string' ? p : JSON.stringify(p))}</li>`).join('')}
                        </ul>
                    `}
                </div>
            ` : ''}
            ${article.novelty_notes ? `
                <div class="expanded-section">
                    <h3>What's Notable</h3>
                    <p class="novelty-text">${escHtml(article.novelty_notes)}</p>
                </div>
            ` : ''}
            <div class="article-card-actions">
                <a href="/article/${article.id}" class="btn btn-secondary btn-sm" onclick="event.stopPropagation()">Full Details</a>
                ${(article.url && (article.url.startsWith('http://') || article.url.startsWith('https://')))
                    ? `<a href="${escHtml(article.url)}" target="_blank" rel="noopener" class="btn btn-primary btn-sm" onclick="event.stopPropagation()">Original &rarr;</a>`
                    : ''}
            </div>
            ${article.summary_text ? `<p class="llm-disclaimer">This summary was generated by Threat Loom using large language models (LLMs). LLMs can make mistakes. Always verify important information against the original source.</p>` : ''}
        </div>
    `;

    return card;
}

function toggleCard(card) {
    const wasExpanded = card.classList.contains('expanded');
    document.querySelectorAll('.article-card.expanded').forEach(c => {
        if (c !== card) c.classList.remove('expanded');
    });
    card.classList.toggle('expanded', !wasExpanded);
}

function escHtml(str) {
    if (!str) return '';
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
}

function extractExecSummary(md) {
    if (!md) return '';
    const match = md.match(/# Executive Summary\s*\n([\s\S]*?)(?=\n#|\n*$)/i);
    return match ? match[1].trim() : md.split('\n').filter(l => l && !l.startsWith('#')).join(' ').substring(0, 300);
}

function renderMarkdown(md) {
    if (!md) return '';
    if (typeof marked !== 'undefined') return marked.parse(md);
    return escHtml(md);
}

// === Search ===
const searchInput = document.getElementById('search-input');
if (searchInput) {
    searchInput.addEventListener('input', debounce(function () {
        currentSearch = this.value.trim();
        currentPage = 1;
        hasMore = true;
        if (currentSearch) {
            showView('search-view');
            const searchTitle = document.getElementById('search-title');
            if (searchTitle) searchTitle.textContent = `Results for "${currentSearch}"`;
            loadArticles();
        } else {
            showCategories();
        }
    }, 400));
}

function clearSearch() {
    const input = document.getElementById('search-input');
    if (input) input.value = '';
    currentSearch = '';
    showCategories();
}

// === Load More / Pagination ===
function loadMore() {
    if (!hasMore || isLoading) return;
    currentPage++;
    loadArticles(true);
}

// Infinite scroll (only for search view)
window.addEventListener('scroll', () => {
    const searchView = document.getElementById('search-view');
    if (!searchView || searchView.style.display === 'none') return;
    if (!hasMore || isLoading) return;
    const scrollBottom = window.innerHeight + window.scrollY;
    if (scrollBottom >= document.body.offsetHeight - 200) {
        currentPage++;
        loadArticles(true);
    }
});

// === Refresh ===
async function triggerRefresh() {
    const btn = document.getElementById('refresh-btn') || document.getElementById('manual-refresh-btn');
    const status = document.getElementById('refresh-status') || document.getElementById('settings-refresh-status');
    const daysInput = document.getElementById('refresh-days') || document.getElementById('settings-refresh-days');
    const days = daysInput ? Math.max(1, Math.min(365, parseInt(daysInput.value) || 1)) : 1;

    if (btn) {
        btn.disabled = true;
        btn.classList.add('loading');
    }
    if (status) {
        status.textContent = `Refreshing (${days}d)...`;
        status.className = 'refresh-status';
    }

    try {
        const res = await fetch('/api/refresh', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ days: days }),
        });
        const data = await res.json();

        if (data.status === 'started') {
            if (status) status.textContent = 'Refresh started, fetching articles...';
            pollRefreshStatus(btn, status);
        } else if (data.status === 'already_running') {
            if (status) status.textContent = 'Refresh already in progress...';
            pollRefreshStatus(btn, status);
        }
    } catch (e) {
        if (status) {
            status.textContent = 'Refresh failed';
            status.className = 'refresh-status error';
        }
        if (btn) {
            btn.disabled = false;
            btn.classList.remove('loading');
        }
    }
}

async function refreshSinceLastRetrieval() {
    const btns = document.querySelectorAll('.refresh-since-btn');
    const status = document.getElementById('refresh-status') || document.getElementById('settings-refresh-status');

    btns.forEach(b => { b.disabled = true; b.classList.add('loading'); });
    if (status) {
        status.textContent = 'Refreshing since last retrieval...';
        status.className = 'refresh-status';
    }

    try {
        const res = await fetch('/api/refresh', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ since_last_fetch: true }),
        });
        const data = await res.json();

        if (data.status === 'started') {
            if (status) status.textContent = 'Refresh started, fetching articles since last retrieval...';
            pollRefreshStatus(btns, status);
        } else if (data.status === 'already_running') {
            if (status) status.textContent = 'Refresh already in progress...';
            pollRefreshStatus(btns, status);
        }
    } catch (e) {
        if (status) {
            status.textContent = 'Refresh failed';
            status.className = 'refresh-status error';
        }
        btns.forEach(b => { b.disabled = false; b.classList.remove('loading'); });
    }
}

function pollRefreshStatus(btn, status) {
    // Normalize btn to an array so callers can pass a single element or a NodeList
    const btns = btn ? (btn.forEach ? btn : [btn]) : [];
    const resetBtns = () => btns.forEach(b => { b.disabled = false; b.classList.remove('loading'); });

    const MAX_POLL_MS = 10 * 60 * 1000;
    const POLL_INTERVAL = 3000;
    const startTime = Date.now();

    const stageLabels = {
        fetch: 'Fetching articles...',
        scrape: 'Scraping article content...',
        confirm: 'Waiting for cost confirmation...',
        summarize: 'Summarizing articles...',
        embed: 'Generating embeddings...',
        done: 'Refresh complete!',
        error: 'Pipeline error',
    };

    const interval = setInterval(async () => {
        if (Date.now() - startTime > MAX_POLL_MS) {
            clearInterval(interval);
            if (status) {
                status.textContent = 'Still processing in background...';
                status.className = 'refresh-status';
                setTimeout(() => { status.textContent = ''; }, 5000);
            }
            resetBtns();
            cachedCategories = null;
            loadCategories();
            loadStats();
            return;
        }

        try {
            loadStats();

            const refreshRes = await fetch('/api/refresh-status');
            const refreshData = await refreshRes.json();

            // Update stage label
            if (status && refreshData.stage && stageLabels[refreshData.stage]) {
                status.textContent = stageLabels[refreshData.stage];
            }

            // Show cost estimate modal if pipeline is waiting for confirmation
            if (refreshData.cost_estimate) {
                showCostEstimateModal(refreshData.cost_estimate);
            }

            // Show actual cost modal after summarization
            if (refreshData.actual_cost) {
                showActualCostModal(refreshData.actual_cost);
            }

            if (!refreshData.is_refreshing) {
                clearInterval(interval);
                if (status) {
                    status.textContent = 'Refresh complete!';
                    status.className = 'refresh-status';
                    setTimeout(() => { status.textContent = ''; }, 5000);
                }
                resetBtns();
                cachedCategories = null;
                loadCategories();
            }
        } catch (e) {
            clearInterval(interval);
            resetBtns();
        }
    }, POLL_INTERVAL);
}

// === Cost Confirmation Modals ===
function showCostEstimateModal(estimate) {
    const modal = document.getElementById('cost-estimate-modal');
    const body = document.getElementById('cost-estimate-body');
    if (!modal || !body) return;
    if (modal.style.display !== 'none') return; // already showing

    body.innerHTML = `<strong>${estimate.article_count}</strong> articles to summarize<br>` +
        `Estimated cost: <strong>$${estimate.estimated_cost.toFixed(4)}</strong><br>` +
        `Model: <strong>${escHtml(estimate.model)}</strong>`;
    modal.style.display = 'flex';
}

function showActualCostModal(costInfo) {
    const modal = document.getElementById('actual-cost-modal');
    const body = document.getElementById('actual-cost-body');
    if (!modal || !body) return;
    if (modal.style.display !== 'none') return; // already showing

    body.innerHTML = `<strong>${costInfo.article_count}</strong> articles summarized<br>` +
        `Actual cost: <strong>$${costInfo.actual_cost.toFixed(4)}</strong><br>` +
        `Model: <strong>${escHtml(costInfo.model)}</strong>`;
    modal.style.display = 'flex';
}

async function approveCost() {
    const modal = document.getElementById('cost-estimate-modal');
    if (modal) modal.style.display = 'none';
    try {
        await fetch('/api/cost/approve', { method: 'POST' });
    } catch (e) {
        console.error('Failed to approve cost:', e);
    }
}

async function declineCost() {
    const modal = document.getElementById('cost-estimate-modal');
    if (modal) modal.style.display = 'none';
    try {
        await fetch('/api/cost/decline', { method: 'POST' });
    } catch (e) {
        console.error('Failed to decline cost:', e);
    }
}

async function dismissActualCost() {
    const modal = document.getElementById('actual-cost-modal');
    if (modal) modal.style.display = 'none';
    try {
        await fetch('/api/cost/dismiss', { method: 'POST' });
    } catch (e) {
        console.error('Failed to dismiss cost:', e);
    }
}

// === Insight Cost Modals ===
let _insightConfirmResolve = null;

function _showInsightEstimateModal(data, type) {
    const modal = document.getElementById('insight-estimate-modal');
    const title = document.getElementById('insight-estimate-title');
    const body = document.getElementById('insight-estimate-body');
    if (!modal || !body) return;

    if (title) title.textContent = type === 'trend' ? 'Trend Analysis Cost Estimate' : 'Forecast Cost Estimate';

    let detail = `<strong>${data.article_count}</strong> article${data.article_count !== 1 ? 's' : ''} will be analyzed`;
    if (type === 'trend' && data.n_quarters !== undefined) {
        detail += `<br><strong>${data.n_quarters}</strong> quarter${data.n_quarters !== 1 ? 's' : ''}, <strong>${data.n_years}</strong> year${data.n_years !== 1 ? 's' : ''} of data`;
    }
    detail += `<br>Maximum estimated cost: <strong>$${data.estimated_cost.toFixed(4)}</strong>`;
    detail += `<br>Model: <strong>${escHtml(data.model)}</strong>`;
    if (type === 'trend') {
        detail += `<br><small style="color:var(--text-secondary)">Cached periods are free. This is a worst-case estimate.</small>`;
    }
    body.innerHTML = detail;
    modal.style.display = 'flex';
}

function insightProceed() {
    document.getElementById('insight-estimate-modal').style.display = 'none';
    if (_insightConfirmResolve) { _insightConfirmResolve(true); _insightConfirmResolve = null; }
}

function insightCancel() {
    document.getElementById('insight-estimate-modal').style.display = 'none';
    if (_insightConfirmResolve) { _insightConfirmResolve(false); _insightConfirmResolve = null; }
}

async function _askInsightConfirm(data, type) {
    _showInsightEstimateModal(data, type);
    return new Promise(resolve => { _insightConfirmResolve = resolve; });
}

function _showInsightActualModal(type, actualCost, articleCount, modelUsed, cached) {
    const modal = document.getElementById('insight-actual-modal');
    const title = document.getElementById('insight-actual-title');
    const body = document.getElementById('insight-actual-body');
    if (!modal || !body) return;

    if (title) title.textContent = type === 'trend' ? 'Trend Analysis Complete' : 'Forecast Complete';

    let detail = `<strong>${articleCount}</strong> article${articleCount !== 1 ? 's' : ''} analyzed`;
    if (cached) {
        detail += `<br>Result loaded from cache <strong>(no cost)</strong>`;
    } else {
        detail += `<br>Actual cost: <strong>$${actualCost.toFixed(4)}</strong>`;
    }
    detail += `<br>Model: <strong>${escHtml(modelUsed)}</strong>`;
    body.innerHTML = detail;
    modal.style.display = 'flex';
}

function dismissInsightActual() {
    const modal = document.getElementById('insight-actual-modal');
    if (modal) modal.style.display = 'none';
}

// === Disclaimer ===
function showDisclaimerIfNeeded() {
    if (!sessionStorage.getItem('disclaimer_accepted')) {
        const modal = document.getElementById('disclaimer-modal');
        if (modal) modal.style.display = 'flex';
    }
}

function dismissDisclaimer() {
    sessionStorage.setItem('disclaimer_accepted', '1');
    const modal = document.getElementById('disclaimer-modal');
    if (modal) modal.style.display = 'none';
}

// === Settings ===
function switchProvider(provider) {
    document.getElementById('llm-provider').value = provider;
    document.querySelectorAll('.provider-btn').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.provider === provider);
    });
    document.getElementById('openai-section').style.display = provider === 'openai' ? '' : 'none';
    document.getElementById('anthropic-section').style.display = provider === 'anthropic' ? '' : 'none';
}

function saveSettings() {
    const apiKey = document.getElementById('api-key');
    const model = document.getElementById('model-select');
    const anthropicKey = document.getElementById('anthropic-key');
    const anthropicModel = document.getElementById('anthropic-model-select');
    const llmProvider = document.getElementById('llm-provider');
    const interval = document.getElementById('fetch-interval');
    const status = document.getElementById('save-status');

    const feedItems = document.querySelectorAll('.feed-item');
    const feeds = [];
    feedItems.forEach(item => {
        const toggle = item.querySelector('.feed-toggle');
        const name = item.querySelector('.feed-name');
        const url = item.querySelector('.feed-url');
        if (name && url) {
            feeds.push({
                name: name.textContent,
                url: url.textContent,
                enabled: toggle ? toggle.checked : true,
            });
        }
    });

    const malpediaKey = document.getElementById('malpedia-key');

    const emailEnabled = document.getElementById('email-enabled');
    const notificationEmail = document.getElementById('notification-email');
    const smtpHost = document.getElementById('smtp-host');
    const smtpPort = document.getElementById('smtp-port');
    const smtpUsername = document.getElementById('smtp-username');
    const smtpPassword = document.getElementById('smtp-password');
    const smtpTls = document.getElementById('smtp-tls');

    // When Anthropic is active, the OpenAI key is entered in the embed-api-key input
    const embedApiKey = document.getElementById('embed-api-key');
    const effectiveOpenAiKey = (llmProvider && llmProvider.value === 'anthropic')
        ? (embedApiKey ? embedApiKey.value : '')
        : (apiKey ? apiKey.value : '');

    const settings = {
        llm_provider: llmProvider ? llmProvider.value : 'openai',
        openai_api_key: effectiveOpenAiKey,
        openai_model: model ? model.value : 'gpt-4o-mini',
        anthropic_api_key: anthropicKey ? anthropicKey.value : '',
        anthropic_model: anthropicModel ? anthropicModel.value : 'claude-3-5-haiku-20241022',
        malpedia_api_key: malpediaKey ? malpediaKey.value : '',
        fetch_interval_minutes: interval ? parseInt(interval.value) : 30,
        feeds: feeds,
        email_notifications_enabled: emailEnabled ? emailEnabled.checked : false,
        notification_email: notificationEmail ? notificationEmail.value : '',
        smtp_host: smtpHost ? smtpHost.value : '',
        smtp_port: smtpPort ? parseInt(smtpPort.value) || 587 : 587,
        smtp_username: smtpUsername ? smtpUsername.value : '',
        smtp_password: smtpPassword ? smtpPassword.value : '',
        smtp_use_tls: smtpTls ? smtpTls.checked : true,
    };

    fetch('/api/settings', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(settings),
    })
        .then(res => res.json())
        .then(data => {
            if (data.status === 'ok') {
                if (status) {
                    status.textContent = 'Settings saved!';
                    status.className = 'save-status success';
                    setTimeout(() => { status.textContent = ''; }, 3000);
                }
            } else {
                throw new Error(data.error || 'Unknown error');
            }
        })
        .catch(e => {
            if (status) {
                status.textContent = 'Failed to save: ' + e.message;
                status.className = 'save-status error';
            }
        });
}

function addFeed() {
    const nameInput = document.getElementById('new-feed-name');
    const urlInput = document.getElementById('new-feed-url');
    const name = nameInput.value.trim();
    const url = urlInput.value.trim();

    if (!name || !url) return;

    if (!url.startsWith('http://') && !url.startsWith('https://')) {
        alert('Invalid URL. Feed URLs must start with http:// or https://');
        return;
    }

    const feedList = document.getElementById('feed-list');
    const item = document.createElement('div');
    item.className = 'feed-item';
    item.innerHTML = `
        <label class="toggle-label">
            <input type="checkbox" class="feed-toggle" checked>
            <span class="toggle-slider"></span>
        </label>
        <div class="feed-info">
            <span class="feed-name">${escHtml(name)}</span>
            <span class="feed-url">${escHtml(url)}</span>
        </div>
        <button class="btn btn-danger btn-sm" onclick="removeFeed(this)" title="Remove feed">&times;</button>
    `;
    feedList.appendChild(item);

    nameInput.value = '';
    urlInput.value = '';
}

function removeFeed(btn) {
    const item = btn.closest('.feed-item');
    if (item) item.remove();
}

async function clearDatabase() {
    if (!confirm('This will delete ALL articles, summaries, and correlations. Sources and settings are kept.\n\nAre you sure?')) {
        return;
    }

    const btn = document.getElementById('clear-db-btn');
    const status = document.getElementById('clear-db-status');

    if (btn) btn.disabled = true;
    if (status) { status.textContent = 'Clearing...'; status.className = 'save-status'; }

    try {
        const res = await fetch('/api/clear-db', { method: 'POST' });
        const data = await res.json();
        if (data.status === 'ok') {
            if (status) { status.textContent = 'Database cleared!'; status.className = 'save-status success'; }
            cachedCategories = null;
            loadCategories();
            loadStats();
        } else {
            throw new Error(data.error || 'Unknown error');
        }
    } catch (e) {
        if (status) { status.textContent = 'Failed: ' + e.message; status.className = 'save-status error'; }
    }
    if (btn) btn.disabled = false;
}

async function testApiKey() {
    const keyInput = document.getElementById('api-key');
    const status = document.getElementById('api-key-status');
    const key = keyInput ? keyInput.value.trim() : '';

    if (!key) {
        if (status) {
            status.textContent = 'Please enter an API key';
            status.className = 'form-hint error';
        }
        return;
    }

    if (status) {
        status.textContent = 'Testing...';
        status.className = 'form-hint';
    }

    try {
        const res = await fetch('/api/test-key', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ api_key: key }),
        });
        const data = await res.json();
        if (data.valid) {
            status.textContent = 'API key is valid!';
            status.className = 'form-hint success';
        } else {
            status.textContent = 'Invalid API key: ' + (data.error || 'Unknown error');
            status.className = 'form-hint error';
        }
    } catch (e) {
        if (status) {
            status.textContent = 'Test failed: ' + e.message;
            status.className = 'form-hint error';
        }
    }
}

async function testAnthropicKey() {
    const keyInput = document.getElementById('anthropic-key');
    const status = document.getElementById('anthropic-key-status');
    const key = keyInput ? keyInput.value.trim() : '';

    if (!key) {
        if (status) {
            status.textContent = 'Please enter an API key';
            status.className = 'form-hint error';
        }
        return;
    }

    if (status) {
        status.textContent = 'Testing...';
        status.className = 'form-hint';
    }

    try {
        const res = await fetch('/api/test-anthropic-key', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ api_key: key }),
        });
        const data = await res.json();
        if (data.valid) {
            status.textContent = 'API key is valid!';
            status.className = 'form-hint success';
        } else {
            status.textContent = 'Invalid API key: ' + (data.error || 'Unknown error');
            status.className = 'form-hint error';
        }
    } catch (e) {
        if (status) {
            status.textContent = 'Test failed: ' + e.message;
            status.className = 'form-hint error';
        }
    }
}

async function testEmbedApiKey() {
    const keyInput = document.getElementById('embed-api-key');
    const status = document.getElementById('embed-api-key-status');
    const key = keyInput ? keyInput.value.trim() : '';

    if (!key) {
        if (status) {
            status.textContent = 'Please enter an API key';
            status.className = 'form-hint error';
        }
        return;
    }

    if (status) {
        status.textContent = 'Testing...';
        status.className = 'form-hint';
    }

    try {
        const res = await fetch('/api/test-key', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ api_key: key }),
        });
        const data = await res.json();
        if (data.valid) {
            status.textContent = 'API key is valid!';
            status.className = 'form-hint success';
        } else {
            status.textContent = 'Invalid API key: ' + (data.error || 'Unknown error');
            status.className = 'form-hint error';
        }
    } catch (e) {
        if (status) {
            status.textContent = 'Test failed: ' + e.message;
            status.className = 'form-hint error';
        }
    }
}

async function testMalpediaKey() {
    const keyInput = document.getElementById('malpedia-key');
    const status = document.getElementById('malpedia-key-status');
    const key = keyInput ? keyInput.value.trim() : '';

    if (!key) {
        if (status) {
            status.textContent = 'Please enter an API key';
            status.className = 'form-hint error';
        }
        return;
    }

    if (status) {
        status.textContent = 'Testing...';
        status.className = 'form-hint';
    }

    try {
        const res = await fetch('/api/test-malpedia-key', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ api_key: key }),
        });
        const data = await res.json();
        if (data.valid) {
            status.textContent = 'API key is valid!';
            status.className = 'form-hint success';
        } else {
            status.textContent = 'Invalid API key: ' + (data.error || 'Unknown error');
            status.className = 'form-hint error';
        }
    } catch (e) {
        if (status) {
            status.textContent = 'Test failed: ' + e.message;
            status.className = 'form-hint error';
        }
    }
}

async function testEmail() {
    const btn = document.getElementById('test-email-btn');
    const status = document.getElementById('test-email-status');

    if (btn) {
        btn.disabled = true;
        btn.classList.add('loading');
    }
    if (status) {
        status.textContent = 'Sending test email...';
        status.className = 'form-hint';
    }

    try {
        const res = await fetch('/api/test-email', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                smtp_host: (document.getElementById('smtp-host') || {}).value || '',
                smtp_port: parseInt((document.getElementById('smtp-port') || {}).value) || 587,
                smtp_username: (document.getElementById('smtp-username') || {}).value || '',
                smtp_password: (document.getElementById('smtp-password') || {}).value || '',
                smtp_use_tls: (document.getElementById('smtp-tls') || {}).checked !== false,
                notification_email: (document.getElementById('notification-email') || {}).value || '',
            }),
        });
        const data = await res.json();
        if (data.success) {
            status.textContent = 'Test email sent! Check your inbox.';
            status.className = 'form-hint success';
        } else {
            status.textContent = 'Failed: ' + (data.error || 'Unknown error');
            status.className = 'form-hint error';
        }
    } catch (e) {
        if (status) {
            status.textContent = 'Test failed: ' + e.message;
            status.className = 'form-hint error';
        }
    }

    if (btn) {
        btn.disabled = false;
        btn.classList.remove('loading');
    }
}
