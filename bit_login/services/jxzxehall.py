import uuid
import json
from datetime import datetime, timedelta
from ..config import CONFIG

TIME_TABLE = [
    [[8, 0], [8, 45]], [[8, 50], [9, 35]], [[9, 55], [10, 40]], [[10, 45], [11, 30]],
    [[11, 35], [12, 20]], [[13, 20], [14, 5]], [[14, 10], [14, 55]], [[15, 15], [16, 0]],
    [[16, 5], [16, 50]], [[16, 55], [17, 40]], [[18, 30], [19, 15]], [[19, 20], [20, 5]],
    [[20, 10], [20, 55]]
]

BUILDING_MAP = {
    "综教A": (39.733193, 116.170654), "综教B": (39.733184, 116.171878), "理教楼": (39.730116, 116.171359),
    "理学A": (39.728886, 116.171800), "理学B": (39.729267, 116.171739), "理学C": (39.729633, 116.171778),
    "文萃楼A": (39.732606, 116.174479), "文萃楼B": (39.732217, 116.174489), "文萃楼C": (39.731655, 116.174267),
    "文萃楼D": (39.731670, 116.173885), "文萃楼E": (39.731669, 116.173429), "文萃楼F": (39.732060, 116.173821),
    "文萃楼G": (39.732216, 116.173101), "文萃楼H": (39.732995, 116.173098), "文萃楼I": (39.733083, 116.173866),
    "文萃楼J": (39.733518, 116.173408), "文萃楼K": (39.733440, 116.173841), "文萃楼L": (39.733488, 116.174220),
    "文萃楼M": (39.733058, 116.174525), "良乡体育馆": (39.731844, 116.176544), "北校区篮球场": (39.736357, 116.170721),
    "南校区篮球场": (39.728026, 116.169304), "南校区排球场": (39.727381, 116.169604), "南校区足球场": (39.729583, 116.169174),
    "南校区网球场": (39.727967, 116.168370), "田径场主席台": (39.729490, 116.168474), "疏桐园A": (39.728834, 116.167727),
    "游泳馆": (39.731755, 116.177294), "化学实验中心": (39.727976, 116.170456), "工训楼": (39.726286, 116.173760),
    "西山阻燃楼": (40.037061, 116.232287), "物理实验中心": (39.729071, 116.170698)
}


class JxzxehallDataError(Exception):
    """The teaching-center endpoint did not return usable application data."""


def _response_json(response, label):
    if not response.ok:
        raise JxzxehallDataError(
            f"教学中心{label}接口返回 HTTP {response.status_code}"
        )
    try:
        value = response.json()
    except ValueError as exc:
        content_type = response.headers.get("Content-Type", "unknown").split(";", 1)[0]
        raise JxzxehallDataError(
            f"教学中心{label}接口未返回 JSON(类型 {content_type});"
            "无法解析"
        ) from exc
    if not isinstance(value, dict):
        raise JxzxehallDataError(f"教学中心{label}接口返回了无效数据")
    return value

def get_weeks(data):
    """根据数据获取周次列表"""
    text = data["ZCMC"]
    if "-" not in text:
        return [int(text[:-1])]
    weeks = []
    for week in text.split(","):
        start, end = week.split("-")
        if not start:
            start = week[:-1]
            end = start
        weeks += list(range(int(start), int(end.replace("周", "")) + 1))
    return weeks

def get_location(data, kch):
    """根据课程号获取上课地点"""
    for course in data:
        if course["KCH"] == kch and "JASMC" in course and course["JASMC"]:
            return course["JASMC"]
    return ""

def get_building_coord(jasmc):
    """根据教室名称关键字匹配获取坐标"""
    if not jasmc:
        return None
    keys = sorted(BUILDING_MAP.keys(), key=len, reverse=True)
    for k in keys:
        if k in jasmc:
            return BUILDING_MAP[k]
    return None

class credit:
    """教务系统学分相关功能操作类"""
    
    def __init__(self, session):
        """初始化"""
        self.session = session

    def get_student_data(self):
        """获取学生基础信息"""
        response = self.session.get(f'{CONFIG["urls"]["active"]["jxzxehall_app"]}/jwapp/sys/xsfacx/modules/pyfacxepg/grpyfacx.do')
        res_json = response.json()
        data = {
            "name": res_json["datas"]["grpyfacx"]["rows"][0]["XM"],
            "student_code": res_json["datas"]["grpyfacx"]["rows"][0]["XH"],
            "major": res_json["datas"]["grpyfacx"]["rows"][0]["ZYDM_DISPLAY"],
            "class": res_json["datas"]["grpyfacx"]["rows"][0]["BJDM_DISPLAY"],
            "grade": res_json["datas"]["grpyfacx"]["rows"][0]["XZNJ_DISPLAY"],
            "gender": res_json["datas"]["grpyfacx"]["rows"][0]["XBDM_DISPLAY"],
            "college": res_json["datas"]["grpyfacx"]["rows"][0]["YXDM_DISPLAY"],
            "total_credit": res_json["datas"]["grpyfacx"]["rows"][0]["ZSYQXF"],
            "completed_credit": res_json["datas"]["grpyfacx"]["rows"][0]["YWCXF"],
            "required_credit": res_json["datas"]["grpyfacx"]["rows"][0]["ZSYQXFXSZ"],
            "id": res_json["datas"]["grpyfacx"]["rows"][0]["WID"],
            "detail": {
                "pyfadm": res_json["datas"]["grpyfacx"]["rows"][0]["PYFADM"],
                "zydm": res_json["datas"]["grpyfacx"]["rows"][0]["ZYDM"],
                "xdlxdm": res_json["datas"]["grpyfacx"]["rows"][0]["XDLXDM"],
                "xdlxdm_display": res_json["datas"]["grpyfacx"]["rows"][0]["XDLXDM_DISPLAY"],
                "xbdm": res_json["datas"]["grpyfacx"]["rows"][0]["XBDM"],
                "xbdm_display": res_json["datas"]["grpyfacx"]["rows"][0]["XBDM_DISPLAY"],
                "zydm_display": res_json["datas"]["grpyfacx"]["rows"][0]["ZYDM_DISPLAY"],
                "yxdm": res_json["datas"]["grpyfacx"]["rows"][0]["YXDM"],
                "yxdm_display": res_json["datas"]["grpyfacx"]["rows"][0]["YXDM_DISPLAY"],
                "wxdm": res_json["datas"]["grpyfacx"]["rows"][0]["WID"],
                "xznj": res_json["datas"]["grpyfacx"]["rows"][0]["XZNJ"],
                "xznj_display": res_json["datas"]["grpyfacx"]["rows"][0]["XZNJ_DISPLAY"],
            }
        }
        return data
    
    def get_credit(self):
        """获取学生学分信息"""
        data = self.get_student_data()
        return {
            "total_credit": data["total_credit"],
            "completed_credit": data["completed_credit"],
            "required_credit": data["required_credit"],
        }
    

class course:
    """教务系统课程相关功能操作类"""
    
    def __init__(self, session):
        """初始化"""
        self.session = session
    def get_courses(self, kksj=None):
        """获取指定或当前学期课程表及排课基础数据"""
        api_headers = CONFIG["headers"]["base"]
        term = kksj
        if not term:
            url = f"{CONFIG['urls']['active']['jxzxehall_app']}/jwapp/sys/wdkbby/modules/jshkcb/dqxnxq.do"
            res = _response_json(
                self.session.get(url, headers=api_headers), "当前学期"
            )
            try:
                term = res["datas"]["dqxnxq"]["rows"][0]["DM"]
            except (KeyError, IndexError, TypeError) as exc:
                raise JxzxehallDataError(
                    "教学中心未返回当前学期；该账号可能不使用本科教学中心"
                ) from exc

        req_param = json.dumps({"XNXQDM": term, "ZC": "1"})
        first_day_res = _response_json(
            self.session.post(
                f"{CONFIG['urls']['active']['jxzxehall_app']}/jwapp/sys/wdkbby/wdkbByController/cxzkbrq.do",
                data={"requestParamStr": req_param},
                headers=api_headers,
            ),
            "校历",
        )
        
        first_day = ""
        for v in first_day_res.get("data", []):
            if v.get("XQ") == 1:
                first_day = v.get("RQ")
                break
        
        schedule_res = _response_json(
            self.session.post(
                f"{CONFIG['urls']['active']['jxzxehall_app']}/jwapp/sys/wdkbby/modules/xskcb/cxxszhxqkb.do?vpn-12-o2-jxzxehallapp.bit.edu.cn",
                data={"XNXQDM": term},
                headers=api_headers,
            ),
            "课程表",
        )
        try:
            rows = schedule_res["datas"]["cxxszhxqkb"]["rows"]
        except (KeyError, TypeError) as exc:
            raise JxzxehallDataError(
                "教学中心未返回本科课程表"
            ) from exc

        return {
            "term": term,
            "firstDay": first_day,
            "data": rows,
        }

    def generate_ics(self, kksj=None):
        """获取课程表并生成ics格式数据及统计说明"""
        schedule = self.get_courses(kksj)
        try:
            first_day = datetime.strptime(schedule["firstDay"], "%Y-%m-%d")
        except (TypeError, ValueError) as exc:
            raise JxzxehallDataError("教学中心未返回有效的学期开始日期") from exc
        class_count = 0
        time_count = 0
        
        calendar = [
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            f"PRODID:BIT101 {datetime.now()}",
            "TZID:Asia/Shanghai",
            "X-WR-CALNAME:BIT101课程表"
        ]
        
        for course in schedule["data"]:
            skzc = course.get("SKZC", "")
            for week, ok in enumerate(skzc):
                if ok == '1':
                    ksjc = course.get("KSJC", 1)
                    jsjc = course.get("JSJC", 1)
                    skxq = course.get("SKXQ", 1)
                    
                    start_hm = TIME_TABLE[ksjc - 1][0]
                    end_hm = TIME_TABLE[jsjc - 1][1]
                    days_add = week * 7 + skxq - 1
                    
                    start_dt = first_day + timedelta(days=days_add, hours=start_hm[0], minutes=start_hm[1])
                    end_dt = first_day + timedelta(days=days_add, hours=end_hm[0], minutes=end_hm[1])
                    
                    jasmc = course.get('JASMC', '')
                    
                    calendar.append("BEGIN:VEVENT")
                    calendar.append(f"SUMMARY:{course.get('KCM', '')}")
                    calendar.append(f"LOCATION:{jasmc}\\n北京理工大学")
                    
                    coord = get_building_coord(jasmc)
                    if coord:
                        calendar.append(f'X-APPLE-STRUCTURED-LOCATION;VALUE=URI;X-ADDRESS="北京理工大学";X-TITLE="{jasmc}":geo:{coord[0]:.6f},{coord[1]:.6f}')
                    
                    calendar.append(f"DESCRIPTION:{course.get('SKJS', '')} | {course.get('YPSJDD', '')}")
                    calendar.append(f"DTSTART;TZID=Asia/Shanghai:{start_dt.strftime('%Y%m%dT%H%M%S')}")
                    calendar.append(f"DTEND;TZID=Asia/Shanghai:{end_dt.strftime('%Y%m%dT%H%M%S')}")
                    calendar.append(f"UID:{uuid.uuid4()}")
                    calendar.append("END:VEVENT")
                    
                    class_count += 1
                    time_count += (jsjc - ksjc + 1) * 45
        
        calendar.append("END:VCALENDAR\n")
        
        ics_content = "\n".join(calendar)
        note = f"一共添加了{schedule['term']}学期的{class_count}节课，合计坐牢时间{time_count / 60:.1f}小时（雾"
        
        return ics_content, note


class classroom:
    """空闲教室查询功能操作类"""
    
    # 状态码翻译字典
    STATUS_MAP = {
        "01": "排课占用",
        "10": "排课占用(特定)",
        "02": "考务占用",
        "03": "其他占用",
        "04": "借用占用",
        "05": "调课占用",
        "11": "特殊排课"
    }

    def __init__(self, session):
        """初始化"""
        self.session = session

    def _parse_jc_status(self, jc_str):
        """解析单个节次的状态码"""
        if not jc_str:
            return "空闲"
            
        occupations = []
        for status in jc_str.split(','):
            if status.startswith('1_'):
                code = status.split('_')[1]
                occupations.append(self.STATUS_MAP.get(code, f"未知占用({code})"))
                
        return " + ".join(occupations) if occupations else "空闲"

    def _get_current_term_info(self):
        """获取当前学期信息"""
        url = f"{CONFIG['urls']['active']['jxzxehall_app']}/jwapp/sys/kxjas/modules/kxjas/dqxnxqcx.do"
        try:
            response = self.session.post(url)
            data = response.json()
            if 'datas' in data and 'dqxnxqcx' in data['datas']:
                rows = data['datas']['dqxnxqcx']['rows']
                if rows:
                    return rows[0]
        except Exception:
            return None
        return None

    def _get_week_info(self, date_str, xn, xq):
        """根据日期获取周次信息"""
        url = f"{CONFIG['urls']['active']['jxzxehall_app']}/jwapp/sys/kxjas/modules/kxjas/rqzhzcjc.do"
        payload = {'RQ': date_str, 'XN': xn, 'XQ': xq}
        try:
            response = self.session.post(url, data=payload)
            data = response.json()
            if 'datas' in data and 'rqzhzcjc' in data['datas']:
                return data['datas']['rqzhzcjc']
        except Exception:
            return None
        return None

    def get_occupancy(self, date_str, semester=None, week=None, 
                      campus_code=None, building_code=None, classroom_name=None):
        """
        获取指定日期、特定条件下的教室占用情况
        :param date_str: 日期 'YYYY-MM-DD'
        :param semester: 学期代码 '2025-2026-2' (可选，默认自动获取)
        :param week: 周次 如 1 (可选，默认自动计算)
        :param campus_code: 校区代码
        :param building_code: 教学楼代码
        :param classroom_name: 教室名称关键字
        """
        try:
            dt = datetime.strptime(date_str, "%Y-%m-%d")
            day_of_week = dt.weekday() + 1 
        except ValueError:
            raise ValueError("日期格式错误，应为 YYYY-MM-DD")
        
        # 自动补全学期和周次信息
        if not semester or not week:
            term_info = self._get_current_term_info()
            if not term_info:
                # 尝试硬编码或从配置读取的默认值? 暂时抛出
                if semester is None or week is None:
                    raise Exception("无法获取当前学期信息，请手动指定 semester 和 week")
            
            # 如果没指定 semester，就用查到的
            if not semester:
                semester = term_info.get("DM") # 例如 "2025-2026-2"
            
            # 如果没指定 week，需查询日期对应的周次
            if not week:
                xn = term_info.get("XNDM") # "2025-2026"
                xq = term_info.get("XQDM") # "2"
                week_info = self._get_week_info(date_str, xn, xq)
                
                # 更新: 优先使用接口返回的星期几(XQJ)，因为它考虑了调休
                if week_info:
                    week = week_info.get("ZC")
                    # XQJ: 1=周一 .. 7=周日
                    if week_info.get("XQJ"):
                        day_of_week = week_info.get("XQJ")
                else:
                    week = 1

        query_setting = []
        if campus_code:
            query_setting.append({"name": "XXXQDM", "caption": "校区代码", "builder": "equal", "linkOpt": "AND", "value": campus_code})
        if building_code:
            query_setting.append({"name": "JXLDM", "caption": "教学楼代码", "builder": "equal", "linkOpt": "AND", "value": building_code})
        if classroom_name:
            query_setting.append({"name": "JASMC", "caption": "教学楼名称", "builder": "include", "linkOpt": "AND", "value": classroom_name})

        all_classrooms = []
        page_number = 1
        page_size = 50
        
        url = f"{CONFIG['urls']['active']['jxzxehall_app']}/jwapp/sys/kxjas/modules/kxjas/cxjsqk.do"
        
        while True:
            payload = {
                'XNXQDM': semester,
                'ZC': week,
                'XQ': day_of_week,
                'RQ': date_str,
                'querySetting': json.dumps(query_setting),
                '*order': '+JASMC',
                'pageSize': page_size,
                'pageNumber': page_number
            }
            
            response = self.session.post(url, data=payload)
            if response.status_code != 200:
                raise Exception(f"请求失败，HTTP 状态码: {response.status_code}")
                
            data = response.json()
            if 'datas' not in data or 'cxjsqk' not in data['datas']:
                raise Exception("获取数据失败，可能是 Cookie 已过期或参数错误")
                
            page_data = data['datas']['cxjsqk']
            rows = page_data.get('rows', [])
            
            for row in rows:
                jasmc = row.get("JASMC")
                room_info = {
                    "name": jasmc,
                    "building_code": row.get("JXLDM"),
                    "type": row.get("JASLXDM_DISPLAY"),
                    "seats": row.get("SKZWS"),
                    "coordinates": get_building_coord(jasmc),
                    "status": {}
                }
                # 遍历全天可能的 1-13 节课
                for i in range(1, 14):
                    jc_key = f"JC{i}"
                    status_str = self._parse_jc_status(row.get(jc_key, ""))
                    
                    # 匹配时间段
                    if i <= len(TIME_TABLE):
                        t_start, t_end = TIME_TABLE[i-1]
                        start_str = f"{t_start[0]:02d}:{t_start[1]:02d}"
                        end_str = f"{t_end[0]:02d}:{t_end[1]:02d}"
                    else:
                        start_str, end_str = "", ""
                        
                    room_info["status"][i] = {
                        "state": status_str,
                        "start": start_str,
                        "end": end_str
                    }
                        
                all_classrooms.append(room_info)
            
            total_size = page_data.get('totalSize', 0)
            if page_number * page_size >= total_size:
                break
            page_number += 1
            
        return all_classrooms
