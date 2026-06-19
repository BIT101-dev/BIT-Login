from bs4 import BeautifulSoup
from ..config import CONFIG
from datetime import datetime
from urllib.parse import urlparse


def get_current_kksj():
    now = datetime.now()
    year = now.year
    if 10 <= now.month <= 12:
        semester = f"{year}-{year + 1}-1"
    if 1 <= now.month <= 3:
        semester = f"{year - 1}-{year}-1"
    if 4 <= now.month <= 9:
        semester = f"{year - 1}-{year}-2"
    return semester


class score:
    def __init__(self,session):
        self.session = session
        
    def _score_list_url(self):
        return f'{CONFIG["urls"]["active"]["jwb_cb"]}jsxsd/kscj/cjcx_list'

    def _score_request_headers(self):
        parsed = urlparse(CONFIG["urls"]["active"]["jwb_cb"])
        origin = f"{parsed.scheme}://{parsed.netloc}"
        return {
            "Origin": origin,
        }
    
    def _post_score_list(self, data):
        response = self.session.post(
            self._score_list_url(),
            data=data,
            headers=self._score_request_headers(),
        )
        if response.status_code >= 500:
            raise Exception(f"成绩列表获取失败: HTTP {response.status_code}")
        return response

    def get_score(self,kksj=None,detailed=False):
        if kksj==None: kksj = get_current_kksj()
        data = {
            'kksj': kksj,
            'kcxz': '',
            'kcmc': '',
            'xsfs': 'all',
        }
        response = self._post_score_list(data)
        res = self.parse_score(response.text,detailed=detailed)
        return res
    
    def get_score_detail(self,url):
        response = self.session.get(url)
        return self.parse_score_detail(response.text)
        
    def parse_score(self,data,detailed=False):
        parser = BeautifulSoup(data, 'html.parser')
        dataList = parser.find(id='dataList')
        if dataList is None: return
        dataList = dataList.find_all('tr')
        nameList = parser.find(id='Top1_divLoginName')
        if nameList is None: return
        student_name = nameList.text
        res=[]
        for data in dataList[1:]:
            data = data.find_all('td')
            t={
                'student':student_name,
                'course':data[3].string,
                'score':data[4].string,
                'credit':data[6].string,
                'hours':data[7].string,
                'kksj':data[1].string, 
                'type':data[11].string,
            }
            # 处理中文score
            if t['score'] == '优秀':
                t['score'] = '95'
            elif t['score'] == '良好':
                t['score'] = '85'
            elif t['score'] == '中等':
                t['score'] = '75'
            elif t['score'] == '及格':
                t['score'] = '65'
            elif t['score'] == '不及格':
                t['score'] = '0'
            # 合并
            if data[-1].find('a') and detailed:
                t.update(self.get_score_detail(CONFIG["urls"]["active"]["jwb_cb"]+data[-1].find('a')["onclick"].split("JsMod('")[1].split("'")[0][1:]))
            else:
                t.update({
                    'class_number': None,
                    'major_number': None,
                    'study_number': None,
                    'average': None,
                    'max': None,
                    'entry_complete': None,
                    'self_score': None,
                    'class_proportion': None,
                    'major_proportion': None,
                    'school_proportion': None,
                })
            res.append(t)
        return res
    
    def parse_score_detail(self,data):
        parser = BeautifulSoup(data, 'html.parser')
        dataLists = parser.find_all(id='dataList')
        
        # Table 2
        table2_rows = dataLists[1].find_all('tr')
        row1_tds = table2_rows[0].find_all('td')
        row2_tds = table2_rows[1].find_all('td')
        
        # Table 3
        table3_tds = dataLists[2].find_all('td')
        
        return {
            'class_number': row1_tds[0].text.split("：")[-1].strip(),
            'major_number': row1_tds[1].text.split("：")[-1].strip(),
            'study_number': row1_tds[2].text.split("：")[-1].strip(),
            'average': row2_tds[0].text.split("：")[-1].strip(),
            'max': row2_tds[1].text.split("：")[-1].strip(),
            'entry_complete': row2_tds[2].text.split("：")[-1].strip(),
            'self_score': table3_tds[0].text.split("：")[-1].strip(),
            'class_proportion': table3_tds[1].text.split("：")[-1].strip(),
            'major_proportion': table3_tds[2].text.split("：")[-1].strip(),
            'school_proportion': table3_tds[3].text.split("：")[-1].strip(),
        }
    
    def get_all_score(self,detailed=False)->list:
        res = self.get_score("",detailed=detailed)
        if not res: return []
        return res
    
    def get_bit101_score(self, kksj=None, detailed=False):
        """获取并返回 bit101 格式的成绩数据"""
        if kksj is None: kksj = ''
        data = {
            'kksj': kksj,
            'kcxz': '',
            'kcmc': '',
            'xsfs': 'all',
        }
        response = self._post_score_list(data)
        return self.parse_bit101_score(response.text, detailed=detailed)

    def parse_bit101_score(self, data, detailed=False):
        """解析 HTML 并转化为 bit101 需要的二维数组结构"""
        parser = BeautifulSoup(data, 'html.parser')
        dataList = parser.find(id='dataList')
        if dataList is None: return []
        
        rows = dataList.find_all('tr')
        if len(rows) == 0: return []
        
        # 定义表头
        base_header = [
            "序号", "开课学期", "课程编号", "课程名称", "成绩", "成绩标识", 
            "学分", "总学时", "考试性质", "考核方式", "课程属性", "课程性质", 
            "课程归属", "课程种类", "是否第一次考试", "操作栏"
        ]
        detail_header = [
            "专业人数", "学习人数", "平均分", "本人成绩", "班级人数", "最高分", 
            "该课程所有教学班成绩录入完毕", "本人成绩在班级中占", "本人成绩在专业中占", "本人成绩在所有学生中占"
        ]
        
        header = base_header + detail_header if detailed else base_header
        res = [header]
        
        for tr in rows[1:]:
            tds = tr.find_all('td')
            row_data = [td.text.strip() for td in tds]
            
            while len(row_data) < 16:
                row_data.append("")
            row_data = row_data[:16]
            
            score = row_data[4]
            if score == '优秀': row_data[4] = '95'
            elif score == '良好': row_data[4] = '85'
            elif score == '中等': row_data[4] = '75'
            elif score == '及格': row_data[4] = '65'
            elif score == '不及格': row_data[4] = '0'
            
            if detailed:
                detail_list = [""] * 10
                a_tag = tds[-1].find('a') if len(tds) > 0 else None
                if a_tag and "onclick" in a_tag.attrs:
                    try:
                        onclick_str = a_tag["onclick"]
                        detail_url = CONFIG["urls"]["active"]["jwb_cb"] + onclick_str.split("JsMod('")[1].split("'")[0][1:]
                        detail_data = self.get_score_detail(detail_url)
                        
                        detail_list[0] = detail_data.get('major_number', '')  # 专业人数
                        detail_list[1] = detail_data.get('study_number', '')  # 学习人数
                        detail_list[2] = detail_data.get('average', '')       # 平均分
                        detail_list[3] = detail_data.get('self_score', '')    # 本人成绩
                        detail_list[4] = detail_data.get('class_number', '')  # 班级人数
                        detail_list[5] = detail_data.get('max', '')           # 最高分
                        detail_list[6] = detail_data.get('entry_complete', '') # 录入完毕
                        detail_list[7] = detail_data.get('class_proportion', '') # 班级占比
                        detail_list[8] = detail_data.get('major_proportion', '') # 专业占比
                        detail_list[9] = detail_data.get('school_proportion', '') # 全校占比
                    except Exception:
                        pass
                row_data.extend(detail_list)
                
            res.append(row_data)
            
        return res

    def get_all_bit101_score(self, detailed=False) -> list:
        """获取所有学期的 bit101 格式成绩"""
        res = self.get_bit101_score("", detailed=detailed)
        if not res: return []
        return res
    
class cjd:
    def __init__(self,session):
        self.session = session

    def get_cjd(self,gpa=True):
        require_gpa = 1 if gpa else 0
        res = self.session.get(f"https://jwb.bit.edu.cn/cjd/ScoreReport2/Index?GPA={require_gpa}").text
        print(res)
        if "以下显示的是本次申请的成绩信息" not in res:
            raise Exception("成绩单获取失败!")
        img_url = "https://jwb.bit.edu.cn/cjd/Temp/"+res.split("<img src=\"/cjd/Temp/")[1].split('" class="img-fluid w-100" a')[0]
        return img_url
        
